//! `fastgame-service.exe`: started by the Windows service manager; `--console` runs it in the foreground.

#[cfg(windows)]
fn main() {
    let console = std::env::args().any(|a| a == "--console");
    let result = if console { fg_service::windows::run_console().map_err(|e| e.to_string()) } else { fg_service::windows::run_as_service().map_err(|e| e.to_string()) };
    if let Err(e) = result {
        eprintln!("fastgame-service: {e}");
        std::process::exit(1);
    }
}

#[cfg(not(windows))]
fn main() {
    eprintln!("fastgame-service only runs on Windows.");
    std::process::exit(1);
}
