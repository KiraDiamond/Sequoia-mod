// Install seccomp after runtime setup, before parsing media.
#[cfg(target_os = "linux")]
pub fn restrict() -> Result<(), std::io::Error> {
    use libc::*;
    #[cfg(target_arch = "x86_64")]
    const AUDIT_ARCH: u32 = 0xc000003e;
    #[cfg(target_arch = "aarch64")]
    const AUDIT_ARCH: u32 = 0xc00000b7;
    unsafe {
        let cpu = rlimit {
            rlim_cur: 20,
            rlim_max: 20,
        };
        let zero = rlimit {
            rlim_cur: 0,
            rlim_max: 0,
        };
        if setrlimit(RLIMIT_CPU, &cpu) != 0
            || setrlimit(RLIMIT_FSIZE, &zero) != 0
            || setrlimit(RLIMIT_CORE, &zero) != 0
        {
            return Err(std::io::Error::last_os_error());
        }
        // Check the syscall architecture first; numbers differ between x86-64 and ARM64.
        // Allow runtime memory, signals, timers, synchronization and existing pipe I/O only.
        let mut f = vec![
            sock_filter {
                code: 0x20,
                jt: 0,
                jf: 0,
                k: 4,
            },
            sock_filter {
                code: 0x15,
                jt: 1,
                jf: 0,
                k: AUDIT_ARCH,
            },
            sock_filter {
                code: 0x06,
                jt: 0,
                jf: 0,
                k: 0x80000000,
            },
            sock_filter {
                code: 0x20,
                jt: 0,
                jf: 0,
                k: 0,
            },
        ];
        for n in [
            SYS_read,
            SYS_write,
            SYS_readv,
            SYS_writev,
            SYS_close,
            SYS_fstat,
            SYS_lseek,
            SYS_mmap,
            SYS_mprotect,
            SYS_munmap,
            SYS_madvise,
            SYS_brk,
            SYS_mremap,
            SYS_rt_sigaction,
            SYS_rt_sigprocmask,
            SYS_rt_sigreturn,
            SYS_sigaltstack,
            SYS_futex,
            SYS_sched_yield,
            SYS_clock_gettime,
            SYS_clock_nanosleep,
            SYS_nanosleep,
            SYS_getpid,
            SYS_gettid,
            SYS_getrandom,
            SYS_restart_syscall,
            SYS_exit,
            SYS_exit_group,
            SYS_set_robust_list,
            SYS_rseq,
        ] {
            f.push(sock_filter {
                code: 0x15,
                jt: 0,
                jf: 1,
                k: n as u32,
            });
            f.push(sock_filter {
                code: 0x06,
                jt: 0,
                jf: 0,
                k: 0x7fff0000,
            });
        }
        f.push(sock_filter {
            code: 0x06,
            jt: 0,
            jf: 0,
            k: 0x00050000 | EPERM as u32,
        });
        let p = sock_fprog {
            len: f.len() as u16,
            filter: f.as_mut_ptr(),
        };
        if prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 {
            return Err(std::io::Error::last_os_error());
        }
        let result = syscall(SYS_seccomp, 1, 1, &p as *const sock_fprog);
        if result != 0 {
            return Err(std::io::Error::last_os_error());
        }
    }
    Ok(())
}
#[cfg(target_os = "windows")]
pub fn restrict() -> Result<(), std::io::Error> {
    Ok(())
}

// Apply Seatbelt after runtime setup so decoding can still use the existing pipes.
// Deny new file access, sockets and subprocesses.
#[cfg(target_os = "macos")]
pub fn restrict() -> Result<(), std::io::Error> {
    use libc::*;
    use std::ffi::CStr;
    #[link(name = "sandbox")]
    unsafe extern "C" {
        fn sandbox_init(profile: *const c_char, flags: u64, error: *mut *mut c_char) -> c_int;
        fn sandbox_free_error(error: *mut c_char);
    }
    unsafe {
        let cpu = rlimit {
            rlim_cur: 20,
            rlim_max: 20,
        };
        let zero = rlimit {
            rlim_cur: 0,
            rlim_max: 0,
        };
        if setrlimit(RLIMIT_CPU, &cpu) != 0
            || setrlimit(RLIMIT_FSIZE, &zero) != 0
            || setrlimit(RLIMIT_CORE, &zero) != 0
        {
            return Err(std::io::Error::last_os_error());
        }
        let profile = c"(version 1)(deny default)(allow signal (target self))";
        let mut error = std::ptr::null_mut();
        if sandbox_init(profile.as_ptr(), 0, &mut error) != 0 {
            let message = if error.is_null() {
                "Seatbelt unavailable".into()
            } else {
                let text = CStr::from_ptr(error).to_string_lossy().into_owned();
                sandbox_free_error(error);
                text
            };
            return Err(std::io::Error::other(message));
        }
    }
    Ok(())
}
