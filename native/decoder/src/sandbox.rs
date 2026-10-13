//! OS sandbox for the decoder process, applied after Wasmtime setup and before media is read.
//!
//! After [`restrict`] the process can still use memory, threads it already started, timers and
//! the pipes it inherited, but cannot open files, create sockets or start processes. Windows
//! applies the equivalent restrictions from outside, through an AppContainer broker.

#[cfg(unix)]
fn limit_resources() -> std::io::Result<()> {
    use rustix::process::{Resource, Rlimit, setrlimit};
    let limit = |seconds| Rlimit {
        current: Some(seconds),
        maximum: Some(seconds),
    };
    setrlimit(Resource::Cpu, limit(20))?;
    // No file may grow and no core dump may be written.
    setrlimit(Resource::Fsize, limit(0))?;
    setrlimit(Resource::Core, limit(0))?;
    Ok(())
}

/// Applies resource limits and a seccomp allowlist to every thread of the process. On error the
/// caller must exit without reading media.
#[cfg(target_os = "linux")]
pub fn restrict() -> std::io::Result<()> {
    use seccompiler::{BpfProgram, SeccompAction, SeccompFilter, TargetArch};
    use std::collections::BTreeMap;

    // Runtime memory, signals, timers, synchronization and I/O on existing descriptors only.
    const ALLOWED: &[libc::c_long] = &[
        libc::SYS_read,
        libc::SYS_write,
        libc::SYS_readv,
        libc::SYS_writev,
        libc::SYS_close,
        libc::SYS_fstat,
        libc::SYS_lseek,
        libc::SYS_mmap,
        libc::SYS_mprotect,
        libc::SYS_munmap,
        libc::SYS_madvise,
        libc::SYS_brk,
        libc::SYS_mremap,
        libc::SYS_rt_sigaction,
        libc::SYS_rt_sigprocmask,
        libc::SYS_rt_sigreturn,
        libc::SYS_sigaltstack,
        libc::SYS_futex,
        libc::SYS_sched_yield,
        libc::SYS_clock_gettime,
        libc::SYS_clock_nanosleep,
        libc::SYS_nanosleep,
        libc::SYS_getpid,
        libc::SYS_gettid,
        libc::SYS_getrandom,
        libc::SYS_restart_syscall,
        libc::SYS_exit,
        libc::SYS_exit_group,
        libc::SYS_set_robust_list,
        libc::SYS_rseq,
    ];

    limit_resources()?;
    let arch = TargetArch::try_from(std::env::consts::ARCH).map_err(std::io::Error::other)?;
    let rules = ALLOWED
        .iter()
        .map(|&call| (call, Vec::new()))
        .collect::<BTreeMap<_, _>>();
    // Another architecture's syscall numbers kill the process; anything unlisted gets EPERM.
    let filter = SeccompFilter::new(
        rules,
        SeccompAction::Errno(libc::EPERM as u32),
        SeccompAction::Allow,
        arch,
    )
    .map_err(std::io::Error::other)?;
    let program = BpfProgram::try_from(filter).map_err(std::io::Error::other)?;
    // TSYNC also covers the deadline thread, which is started before this point.
    seccompiler::apply_filter_all_threads(&program).map_err(std::io::Error::other)
}

/// Applies resource limits and a deny-by-default Seatbelt profile.
#[cfg(target_os = "macos")]
pub fn restrict() -> std::io::Result<()> {
    use std::ffi::{CStr, c_char, c_int};

    #[link(name = "sandbox")]
    unsafe extern "C" {
        fn sandbox_init(profile: *const c_char, flags: u64, error: *mut *mut c_char) -> c_int;
        fn sandbox_free_error(error: *mut c_char);
    }

    limit_resources()?;
    let profile = c"(version 1)(deny default)(allow signal (target self))";
    let mut error: *mut c_char = std::ptr::null_mut();
    // SAFETY: `profile` is a NUL-terminated static string, flags 0 selects a literal profile,
    // and `error` is a valid out-pointer that libsandbox sets to null or to a C string it owns.
    let status = unsafe { sandbox_init(profile.as_ptr(), 0, &mut error) };
    if status == 0 {
        return Ok(());
    }
    if error.is_null() {
        return Err(std::io::Error::other("Seatbelt unavailable"));
    }
    // SAFETY: on failure libsandbox returned a NUL-terminated message that stays valid until
    // `sandbox_free_error`, which is called exactly once after the message is copied.
    let message = unsafe {
        let text = CStr::from_ptr(error).to_string_lossy().into_owned();
        sandbox_free_error(error);
        text
    };
    Err(std::io::Error::other(message))
}

/// Windows confinement is applied by the AppContainer broker that starts this process.
#[cfg(target_os = "windows")]
pub fn restrict() -> std::io::Result<()> {
    Ok(())
}
