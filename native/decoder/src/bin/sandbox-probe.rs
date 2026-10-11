//! Native verification probe, built for CI and never shipped in the mod.
//!
//! Applies the decoder's exact OS sandbox and checks that file, socket and process operations
//! are denied while memory and inherited pipes keep working. Each operation is first shown to
//! work before the sandbox, so a later failure is the sandbox's doing and not the environment's.
//!
//! Usage: `sandbox-probe <readable-file> <path-to-create> <loopback-port>`

use std::fs::File;
use std::io::{ErrorKind, Write};
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::{Command, ExitCode, Stdio};
use std::time::Duration;

#[cfg(windows)]
fn main() -> ExitCode {
    eprintln!("Windows confinement is verified through the AppContainer broker probe");
    ExitCode::FAILURE
}

#[cfg(unix)]
fn main() -> ExitCode {
    match probe() {
        Ok(report) => {
            println!("OS_SANDBOX_DENIALS_OK: {report}");
            ExitCode::SUCCESS
        }
        Err(failure) => {
            eprintln!("OS sandbox probe failed: {failure}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(unix)]
fn probe() -> Result<String, String> {
    let mut args = std::env::args_os().skip(1);
    let (Some(readable), Some(created), Some(port)) = (args.next(), args.next(), args.next())
    else {
        return Err("usage: sandbox-probe <readable-file> <path-to-create> <loopback-port>".into());
    };
    let readable = PathBuf::from(readable);
    let created = PathBuf::from(created);
    let port: u16 = port
        .to_str()
        .and_then(|text| text.parse().ok())
        .ok_or("invalid port")?;
    let loopback = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);

    std::fs::read(&readable).map_err(|e| format!("control read failed: {e}"))?;
    if created.exists() {
        return Err("the path to create already exists".into());
    }
    TcpStream::connect_timeout(&loopback.into(), Duration::from_secs(2))
        .map_err(|e| format!("control loopback connect failed: {e}"))?;
    Command::new("/bin/sh")
        .args(["-c", "exit 0"])
        .stdin(Stdio::null())
        .status()
        .map_err(|e| format!("control process start failed: {e}"))?;

    seqimages_decoder::sandbox::restrict().map_err(|e| format!("restrict failed: {e}"))?;

    let mut denied = Vec::new();
    expect_denied("file read", File::open(&readable).map(drop))?;
    denied.push("file read");
    expect_denied("file create", File::create(&created).map(drop))?;
    if created.exists() {
        return Err("file create left a file behind".into());
    }
    denied.push("file create");
    expect_denied(
        "socket bind",
        TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).map(drop),
    )?;
    denied.push("socket bind");
    expect_denied(
        "loopback connect",
        TcpStream::connect_timeout(&loopback.into(), Duration::from_secs(2)).map(drop),
    )?;
    denied.push("loopback connect");
    expect_denied(
        "process start",
        Command::new("/bin/sh")
            .args(["-c", "exit 0"])
            .stdin(Stdio::null())
            .status()
            .map(drop),
    )?;
    denied.push("process start");

    // The decoder still needs memory growth and its inherited pipes.
    let buffer = vec![1u8; 64 * 1024 * 1024];
    if buffer.iter().step_by(4096).any(|&byte| byte != 1) {
        return Err("memory allocation misbehaved".into());
    }
    std::io::stdout()
        .flush()
        .map_err(|e| format!("inherited stdout unusable: {e}"))?;
    Ok(denied.join(", "))
}

#[cfg(unix)]
fn expect_denied(what: &str, result: std::io::Result<()>) -> Result<(), String> {
    match result {
        Ok(()) => Err(format!("{what} was allowed")),
        // seccomp returns EPERM and Seatbelt EPERM or EACCES; both surface here.
        Err(error) if error.kind() == ErrorKind::PermissionDenied => Ok(()),
        Err(error) => Err(format!("{what} failed for another reason: {error}")),
    }
}
