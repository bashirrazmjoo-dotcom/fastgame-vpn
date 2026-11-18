//! Prints the sing-box config for a link: `cargo run --example dump_config -- "<vless link>" <server-ip> [proxy-port]`.
//! Used by CI to feed the result to `sing-box check`.
use fg_core::{link::VlessLink, singbox};

fn main() {
    let mut args = std::env::args().skip(1);
    let link = VlessLink::parse(&args.next().expect("link")).expect("valid link");
    let ip = args.next().expect("server ip").parse().expect("ip");
    let opts = singbox::TunOptions::default();
    // An optional third argument is a port: print the proxy-mode config instead of the tunnel one.
    let cfg = match args.next() {
        Some(port) => singbox::build_proxy(&link, ip, port.parse().expect("port"), &opts).expect("config"),
        None => singbox::build(&link, ip, &opts).expect("config"),
    };
    println!("{}", serde_json::to_string_pretty(&cfg).unwrap());
}
