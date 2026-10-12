//! Native verification probe, built for CI and never shipped in the mod.
//!
//! Applies the decoder's exact OS sandbox and checks that file, socket and process operations
//! are denied while memory and inherited pipes keep working. Each operation first runs before
//! the sandbox, so a later failure is the sandbox's doing and not the environment's.
//!
//! Usage: `sandbox-probe <readable-file> <path-to-create> <loopback-port>`

use std::process::ExitCode;

#[cfg(windows)]
fn main() -> ExitCode {
    eprintln!("Windows confinement is verified through the AppContainer broker probe");
    ExitCode::FAILURE
}

#[cfg(unix)]
fn main() -> ExitCode {
    match probe() {
        Ok(denied) => {
            println!("OS_SANDBOX_DENIALS_OK: {denied}");
            ExitCode::SUCCESS
        }
        Err(failure) => {
            eprintln!("OS sandbox probe failed: {failure}");
            ExitCode::FAILURE
        }
    }
}

/// An operation the sandbox must deny, run once on an unconfined control target first.
#[cfg(unix)]
struct Check {
    name: &'static str,
    run: Box<dyn Fn(bool) -> std::io::Result<()>>,
}

#[cfg(unix)]
fn checks(
    readable: std::path::PathBuf,
    created: std::path::PathBuf,
    loopback: std::net::SocketAddrV4,
) -> Vec<Check> {
    use std::fs::File;
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::process::{Command, Stdio};
    use std::time::Duration;

    let control = created.with_extension("control");
    vec![
        Check {
            name: "file read",
            run: Box::new(move |_| File::open(&readable).map(drop)),
        },
        Check {
            name: "file create",
            // The control creates a sibling, so the denied path never exists beforehand.
            run: Box::new(move |confined| {
                let target = if confined { &created } else { &control };
                File::create(target)?;
                std::fs::remove_file(target)
            }),
        },
        Check {
            name: "socket bind",
            run: Box::new(|_| TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).map(drop)),
        },
        Check {
            name: "loopback connect",
            run: Box::new(move |_| {
                TcpStream::connect_timeout(&loopback.into(), Duration::from_secs(2)).map(drop)
            }),
        },
        Check {
            name: "process start",
            run: Box::new(|_| {
                Command::new("/bin/sh")
                    .args(["-c", "exit 0"])
                    .stdin(Stdio::null())
                    .status()
                    .map(drop)
            }),
        },
    ]
}

#[cfg(unix)]
fn probe() -> Result<String, String> {
    use std::io::{ErrorKind, Write};
    use std::net::{Ipv4Addr, SocketAddrV4};
    use std::path::PathBuf;

    let mut args = std::env::args_os().skip(1);
    let (Some(readable), Some(created), Some(port)) = (args.next(), args.next(), args.next())
    else {
        return Err("usage: sandbox-probe <readable-file> <path-to-create> <loopback-port>".into());
    };
    let created = PathBuf::from(created);
    if created.exists() {
        return Err("the path to create already exists".into());
    }
    let port: u16 = port
        .to_str()
        .and_then(|text| text.parse().ok())
        .ok_or("invalid port")?;
    let checks = checks(
        PathBuf::from(readable),
        created.clone(),
        SocketAddrV4::new(Ipv4Addr::LOCALHOST, port),
    );
    for check in &checks {
        (check.run)(false).map_err(|e| format!("control {} failed: {e}", check.name))?;
    }

    seqimages_decoder::sandbox::restrict().map_err(|e| format!("restrict failed: {e}"))?;

    for check in &checks {
        match (check.run)(true) {
            Ok(()) => return Err(format!("{} was allowed", check.name)),
            // seccomp returns EPERM and Seatbelt EPERM or EACCES; both surface here.
            Err(error) if error.kind() == ErrorKind::PermissionDenied => {}
            Err(error) => return Err(format!("{} failed for another reason: {error}", check.name)),
        }
    }
    if created.exists() {
        return Err("file create left a file behind".into());
    }
    // The decoder still needs memory growth and its inherited pipes.
    let buffer = vec![1u8; 64 * 1024 * 1024];
    if buffer.iter().step_by(4096).any(|&byte| byte != 1) {
        return Err("memory allocation misbehaved".into());
    }
    std::io::stdout()
        .flush()
        .map_err(|e| format!("inherited stdout unusable: {e}"))?;
    Ok(checks
        .iter()
        .map(|check| check.name)
        .collect::<Vec<_>>()
        .join(", "))
}
