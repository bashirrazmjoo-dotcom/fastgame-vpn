# ics-openvpn inside FastGame VPN

This directory contains a modified copy of **OpenVPN for Android (ics-openvpn)** by Arne Schwabe,
upstream commit `660901c` (https://github.com/schwabe/ics-openvpn), licensed under the GNU GPL v2
with additional terms — see `LICENSE.txt`. FastGame VPN is therefore distributed under the same
license, and its complete source code is public.

Native dependencies (openvpn, openssl, mbedtls, openvpn3, asio, lz4, fmt) are git submodules under
`main/src/main/cpp/`, pinned to the same commits as upstream.

## Changes made by FastGame VPN
- `main/build.gradle.kts`: built as an Android library; one flavor; ABIs limited to arm64-v8a and
  armeabi-v7a; no signing/bundle configuration; minSdk 26.
- `main/src/main/AndroidManifest.xml`: removed the external-control API, boot receiver, exported
  activities/aliases, and the QUERY_ALL_PACKAGES / storage / local-network permissions.
- Removed: the original UI (`src/ui`), tests, translations tooling, `remoteExample`.
