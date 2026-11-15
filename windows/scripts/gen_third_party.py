#!/usr/bin/env python3
"""Writes THIRD-PARTY-LICENSES.txt: bundled programs plus every Rust crate linked into the app
(name, version, license expression from Cargo metadata). Run from the `windows/` folder:
    python scripts/gen_third_party.py <sing-box-LICENSE> <out-file>
"""
import json, subprocess, sys
from pathlib import Path

def main(singbox_license: str, out: str) -> None:
    meta = json.loads(subprocess.check_output(["cargo", "metadata", "--format-version", "1", "--locked"], text=True, encoding="utf-8"))
    ours = {"fg-core", "fg-service", "fg-app", "fastgame"}
    crates = sorted({(p["name"], p["version"], p.get("license") or "UNKNOWN", p.get("repository") or "") for p in meta["packages"] if p["name"] not in ours})
    parts = [
        "FastGame VPN for Windows is licensed under the GNU GPL v2 (see LICENSE).\n"
        "Source code: https://github.com/bashirrazmjoo-dotcom/fastgame-vpn\n",
        "=" * 72 + "\nBundled programs (separate executables, started by the FastGame service)\n" + "=" * 72,
        "sing-box - https://github.com/SagerNet/sing-box\nGNU GPL v3 or later, with the additional term that derivative works may not use the\nname or imply association with the application without prior consent. The unmodified\nofficial release is shipped; its source is at the address above.\n\n" + Path(singbox_license).read_text(encoding="utf-8", errors="replace"),
        "Wintun (embedded in sing-box) - https://www.wintun.net\nCopyright (C) 2018-2024 WireGuard LLC. Distributed under the Wintun prebuilt binaries license.\n",
        "Vazirmatn font - https://github.com/rastikerdar/vazirmatn\nSIL Open Font License 1.1 (see Vazirmatn-OFL.txt).\n",
        "Microsoft Edge WebView2 Runtime - installed from Microsoft when it is missing; Microsoft license terms apply.\n",
        "=" * 72 + "\nRust crates linked into the application\n" + "=" * 72,
    ]
    parts += [f"{n} {v} - {l}  {r}".rstrip() for n, v, l, r in crates]
    Path(out).write_text("\n".join(parts) + "\n", encoding="utf-8")
    unknown = [c for c in crates if c[2] == "UNKNOWN"]
    print(f"{len(crates)} crates, {len(unknown)} without license metadata")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
