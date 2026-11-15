//! Prints the sing-box config for a link: `cargo run --example dump_config -- "<vless link>" <server-ip>`.
//! Used by CI to feed the result to `sing-box check`.
use fg_core::{link::VlessLink, singbox};

fn main() {
    let mut args = std::env::args().skip(1);
    let link = VlessLink::parse(&args.next().expect("link")).expect("valid link");
    let ip = args.next().expect("server ip").parse().expect("ip");
    let cfg = singbox::build(&link, ip, &singbox::TunOptions::default()).expect("config");
    println!("{}", serde_json::to_string_pretty(&cfg).unwrap());
}
