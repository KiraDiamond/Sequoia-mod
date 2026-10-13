//! Native verification probe, built for CI and never shipped in the mod.
//!
//! Applies the decoder's exact OS sandbox and checks that file, socket and process operations
//! are denied. Each operation first runs before the sandbox, so a later failure is the
//! sandbox's doing and not the environment's; printing the result proves stdout still works.
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

/// Attempts each operation the sandbox must deny, creating and removing `created`.
#[cfg(unix)]
fn attempts(
    readable: &std::path::Path,
    created: &std::path::Path,
    loopback: std::net::SocketAddrV4,
) -> [(&'static str, std::io::Result<()>); 5] {
    use std::fs::File;
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::process::{Command, Stdio};
    use std::time::Duration;

    [
        ("file read", File::open(readable).map(drop)),
        (
            "file create",
            File::create(created).and_then(|_| std::fs::remove_file(created)),
        ),
        (
            "socket bind",
            TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).map(drop),
        ),
        (
            "loopback connect",
            TcpStream::connect_timeout(&loopback.into(), Duration::from_secs(2)).map(drop),
        ),
        (
            "process start",
            Command::new("/bin/sh")
                .args(["-c", "exit 0"])
                .stdin(Stdio::null())
                .status()
                .map(drop),
        ),
    ]
}

#[cfg(unix)]
fn probe() -> Result<String, String> {
    use std::io::ErrorKind;
    use std::net::{Ipv4Addr, SocketAddrV4};
    use std::path::PathBuf;

    let mut args = std::env::args_os().skip(1);
    let (Some(readable), Some(created), Some(port)) = (args.next(), args.next(), args.next())
    else {
        return Err("usage: sandbox-probe <readable-file> <path-to-create> <loopback-port>".into());
    };
    let (readable, created) = (PathBuf::from(readable), PathBuf::from(created));
    if created.exists() {
        return Err("the path to create already exists".into());
    }
    let port: u16 = port
        .to_str()
        .and_then(|text| text.parse().ok())
        .ok_or("invalid port")?;
    let loopback = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
    // The control creates a sibling, so the denied path never exists beforehand.
    for (name, result) in attempts(&readable, &created.with_extension("control"), loopback) {
        result.map_err(|e| format!("control {name} failed: {e}"))?;
    }

    seqimages_decoder::sandbox::restrict().map_err(|e| format!("restrict failed: {e}"))?;

    let confined = attempts(&readable, &created, loopback);
    for (name, result) in &confined {
        match result {
            Ok(()) => return Err(format!("{name} was allowed")),
            // seccomp returns EPERM and Seatbelt EPERM or EACCES; both surface here.
            Err(error) if error.kind() == ErrorKind::PermissionDenied => {}
            Err(error) => return Err(format!("{name} failed for another reason: {error}")),
        }
    }
    if created.exists() {
        return Err("file create left a file behind".into());
    }
    Ok(confined.map(|(name, _)| name).join(", "))
}
