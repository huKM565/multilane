<p align="center">
  <img src="androidApp/src/main/res/playstore_icon.png" alt="MultiLane" width="112" height="112">
</p>

<h1 align="center">MultiLane</h1>

<p align="center">
  <b>Multi-room olcRTC bonding &amp; multi-protocol VPN client.</b><br>
  Android, Windows, Linux, macOS, and headless Linux / OpenWrt routers (<code>multilane-cli</code>).
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/huKM565/multilane?color=b5f23d&labelColor=07080d" alt="License"></a>
  <a href="https://github.com/huKM565/multilane/releases"><img src="https://img.shields.io/github/v/release/huKM565/multilane?color=b5f23d&labelColor=07080d&label=release" alt="Latest release"></a>
</p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#protocols">Protocols</a> ·
  <a href="#multilane-cli">CLI &amp; Routers</a> ·
  <a href="#routing">Routing</a> ·
  <a href="#download">Download</a> ·
  <a href="#building-from-source">Build</a>
</p>

**MultiLane** is a fork of [Ghostlane](https://github.com/ghostlane-project/ghostlane) (built on [alananisimov/olcbox](https://github.com/alananisimov/olcbox)) focused on **multi-room olcRTC bonding** — aggregating 4, 8, or 12 WebRTC call rooms in parallel behind Xray `leastLoad` balancing and a `sing-box` routing front-end — alongside standard VPN protocols and a standalone static Go CLI for Linux servers and OpenWrt routers.

It is a client, not a service: you bring your own subscription URL, QR code, or server link (`multilane://`, `ghostlane://`, `olcrtc://`, `vless://`, `hysteria2://`, `trojan://`, `ss://`, `vmess://`).

## Features

- **Multi-room olcRTC bonding (4x / 8x / 12x):** opens parallel WebRTC rooms and balances TCP streams across all active rooms simultaneously via Xray `leastLoad` + `observatory`, with automatic dead-room recovery and live room status/occupancy indicators.
- **Multi-protocol fallback:** supports olcRTC, VLESS (Reality, TLS, plain, TCP/gRPC/WS/HTTPUpgrade/HTTP/XHTTP), Hysteria2, Trojan, Shadowsocks, and VMess in one app.
- **Smart connect & failover:** probes transports before connecting (including 64 KB DPI stall checks and whitelist detection) and automatically fails over behind the active TUN interface without dropping traffic.
- **Split routing & custom rules:** All traffic, Bypass Russia / Iran / China, Only blocked sites (Re:filter), plus custom domain and IP/CIDR direct/tunnel rules.
- **Cross-platform GUI:** Kotlin Multiplatform + Compose UI for Android (including Android TV, Quick Settings tile, Always-on VPN, per-app split tunneling), Windows (Wintun + UAC elevation), Linux (`hev-socks5-tunnel` + optional kill switch), and macOS (TUN daemon / system proxy).
- **Headless CLI (`multilane-cli`):** single static Go binary (`amd64` / `arm64` / `armv7`) with embedded `sing-box`, `Xray-core`, and `olcRTC`, designed for Linux servers and OpenWrt routers (including Podkop-style selective routing).
- **LAN proxy sharing:** share the desktop tunnel over the local network as an authenticated SOCKS5 proxy pinned to the trusted gateway.
- **Router export:** export subscription servers directly for Podkop (OpenWrt / sing-box) and XKeen (Keenetic / Xray).

## Protocols

| Protocol | Engine |
| --- | --- |
| olcRTC (single room or 4x/8x/12x bonded pool) | `olcRTC` (+ Xray `leastLoad` + `sing-box` for bonded pools) |
| VLESS + Reality (TCP, gRPC, HTTP/2) | `sing-box` |
| VLESS + TLS / plain (TCP, WS, HTTPUpgrade, HTTP/2) | `sing-box` |
| VLESS + XHTTP (Reality, TLS, plain) | `Xray-core` |
| Hysteria2 (with Salamander obfs) | `sing-box` |
| Trojan, Shadowsocks (AEAD / 2022), VMess | `sing-box` |

Supported olcRTC carriers: Yandex Telemost, WB Stream, Sber SaluteJazz, VK Calls, MTS Link, and Jitsi (Mamba).

## `multilane-cli`

Headless daemon + CLI for Linux servers and OpenWrt routers (`amd64`, `arm64`, `armv7`).

```sh
multilane-cli add 'https://…/sub/…'
multilane-cli list
multilane-cli connect DE --proxy              # local SOCKS5 + HTTP on 127.0.0.1:1080
multilane-cli connect DE --tun                # system TUN (multilane0)
multilane-cli connect DE --tun --kill-switch  # block non-tunnel traffic while down
multilane-cli status
multilane-cli disconnect
```

Daemon flags for selective routing on routers / gateways:
- `--direct-rules /etc/multilane-direct.rules` — domains, IPs/CIDRs, or `.srs` rule sets routed directly (everything else through tunnel).
- `--tunnel-rules /etc/multilane-tunnel.rules` — Podkop-style selective mode: only listed domains, IPs/CIDRs, or `.srs` rule sets go through the tunnel; everything else goes direct.
- `--connect <selector> --mode tun` — auto-connect on daemon start using stored subscriptions (with or without `--subscription`).

Full CLI documentation: [docs/cli.md](docs/cli.md) and [cli/README.md](cli/README.md).

## Routing

Available in **Settings → Routing**:

| Mode | Behavior |
| --- | --- |
| All traffic | Everything goes through the tunnel. |
| Bypass Russia / Iran / China | Regional domains, TLDs, IPs, and private networks go direct; everything else rides the tunnel. |
| Only blocked sites | Blocked domains and IP ranges (Re:filter) go through the tunnel; everything else goes direct. |
| Custom rules | User-defined domains and IP/CIDR prefixes always win over mode lists. |

Rule sets (`v2fly` regional lists and `Re:filter`) are bundled offline inside the app.

## Server lists

Import via clipboard paste, QR code, file, or deep link:

```text
multilane://add?url=<payload>
multilane://add/<server-list URL>
ghostlane://add?url=<payload>
```

Reads standard subscription headers (`profile-title`, `subscription-userinfo`, `support-url`, `profile-web-page-url`, `profile-update-interval`, `announce`, `fallback-url`, `new-url` / `new-domain`).

## Download

Prebuilt binaries are published on [GitHub Releases](https://github.com/huKM565/multilane/releases):

| Platform | Artifacts |
| --- | --- |
| Android | Universal APK, `armeabi-v7a` APK |
| Windows | EXE installer, MSI installer, portable ZIP |
| Linux Desktop | AppImage (`amd64`) |
| macOS | DMG (`arm64`, `amd64`) |
| Linux / Router CLI | `multilane-cli` static binaries & packages (`amd64`, `arm64`, `armv7`) |

## Building from source

### Requirements

- **JDK 21** (Android target/source level 17)
- **Go 1.26.5** (for `olcrtc`, `sing-box`, and `multilane-cli`)
- **Android SDK 37 & NDK 28.2.13676358** (for Android builds)
- Local `olcrtc` checkout at `../olcrtc` (or set `OLCRTC_REPO=/path/to/olcrtc`)

### Commands

| Task | Command |
| --- | --- |
| JVM unit tests | `./gradlew :sharedUI:jvmTest` |
| Desktop compile / run | `./gradlew :desktopApp:compileKotlin` / `./gradlew :desktopApp:run` |
| Desktop Linux AppImage | `./gradlew :desktopApp:packageReleaseLinuxAppImage` |
| Desktop Windows installers | `.\gradlew.bat :desktopApp:packageReleaseExe :desktopApp:packageReleaseMsi :desktopApp:packageReleasePortableZip` |
| Android debug APK | `./gradlew :androidApp:assembleGithubDebug` |
| Android release APK | `./gradlew :androidApp:assembleGithubRelease` |
| Go CLI build & test | `cd cli && make vet && make test && make build` |
| Go CLI cross-build (`amd64`/`arm64`/`arm7`) | `cd cli && make build-all` |

### Build Outputs

| Target | Output |
| --- | --- |
| Android debug | `androidApp/build/outputs/apk/github/debug/androidApp-github-debug.apk` |
| Android release | `androidApp/build/outputs/apk/github/release/androidApp-github-release.apk` |
| Linux AppImage | `desktopApp/build/compose/binaries/main-release/appimage/MultiLane-<version>-linux-<arch>.AppImage` |
| Windows EXE / MSI / ZIP | `desktopApp\build\compose\binaries\main-release\{exe,msi,portable}\MultiLane-*` |
| macOS DMG | `desktopApp/build/compose/binaries/main-release/dmg/MultiLane-<version>.dmg` |
| Go CLI | `cli/dist/multilane-cli` / `cli/dist/linux-<arch>/multilane-cli` |

> Note: `sing-box` (patched via `scripts/build-singbox.sh`) and `Xray-core` binaries must be placed in `desktopApp/src/main/resources/native/` for desktop non-olcRTC / bonded transports, or `androidApp/jniLibs/<abi>/{libsingboxcore.so,libxraycore.so}` for Android.

## Credits & License

MultiLane is based on [Ghostlane](https://github.com/ghostlane-project/ghostlane) and [alananisimov/olcbox](https://github.com/alananisimov/olcbox), powered by [olcRTC](https://github.com/ghostlane-project/olcrtc), [sing-box](https://github.com/SagerNet/sing-box), and [Xray-core](https://github.com/XTLS/Xray-core).

See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for license details.
