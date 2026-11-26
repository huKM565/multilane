# Mixed SOCKS5 + HTTP Proxy and TUN Toggle Across MultiLane

## Goal

Expose a local `mixed` (SOCKS5 + HTTP) proxy on a single port (`127.0.0.1:10808` in GUI apps, `127.0.0.1:1080` in CLI) whenever connected, regardless of whether TUN mode is enabled or disabled, and allow disabling TUN via a checkbox/switch in Settings (enabled by default) without mutating OS system proxy settings.

## Decisions

1. **Single mixed port**: One listening port accepts both SOCKS5 (`0x05`) and HTTP (`CONNECT` / plain HTTP proxy requests) simultaneously on all platforms (`desktopApp`, `androidApp`, `iosApp`, `cli`). `DesktopLanProxy` also uses `type = "mixed"`.
2. **TUN toggle, default ON**: Replace OS-gated connection mode pickers with a single TUN toggle enabled by default across Desktop (Windows, macOS, Linux), Android, and iOS.
3. **No OS system proxy mutation**: Disabling TUN stops routing system traffic through a TUN interface and leaves OS/PAC proxy settings untouched (`MacOsProxyController` / `WindowsProxyController` / `PacServer` / `NEProxySettings` are not applied).
4. **Visible, passwordless local proxy by default**: Remove `AdminState.configuratorVisible` gates hiding local proxy settings and stop auto-generating hidden 24-character credentials for `127.0.0.1` on Android and iOS (keep empty username/password valid on loopback; LAN sharing keeps mandatory generated credentials).

## Architecture by Layer

### 1. sing-box and LAN Configs (`sharedUI/commonMain`, `sharedUI/jvmMain`)

- `SingBoxConfig.render`: Change inbound `"in"` from `"socks"` to `"mixed"`. Support an optional internal `tunInbound` (`"socks"`) when TUN/hev needs a separate authenticated loopback port while `"in"` exposes the user's configured port and optional login.
- `SingBoxConfig.buildTun` / `renderTun`: Accept optional `proxyPort` and `proxyLogin` to emit an additional `"proxy-in"` (`type = "mixed"`) inbound alongside `"tun-in"` (used by iOS when TUN is ON).
- `DesktopLanProxy`: Change `"lan-in"` inbound from `"socks"` to `"mixed"`.

### 2. Mixed Proxy Front (`MixedProxyBridge`)

When an engine or bonded chain cannot bind a native `sing-box` `mixed` inbound directly (single-room `olcrtc` or standalone `Xray` XHTTP), run the upstream SOCKS5 core on an internal loopback port and run a lightweight bridge on `127.0.0.1:<userPort>`:

- `sharedUI/jvmAndroidMain/.../net/MixedProxyBridge.kt` for Desktop and Android.
- `iosApp/PacketTunnel/MixedProxyBridge.swift` for iOS PacketTunnel extension (keeps extension memory comfortably under the 50 MB iOS cap without launching a second Go runtime).

Protocol handling:
- First byte `0x05`: SOCKS5 handshake passthrough with optional credential verification on the client side and upstream credential translation (including `UDP ASSOCIATE`).
- Otherwise: HTTP proxy parser validating optional `Proxy-Authorization: Basic` (`407 Proxy Authentication Required` on mismatch), handling `CONNECT host:port` and absolute-URI HTTP requests over upstream SOCKS5 `CONNECT`.

### 3. Desktop (`sharedUI/jvmMain`, `desktopApp`)

- Allow `DesktopConnectionMode.Proxy` on `DesktopOs.Linux`, `DesktopOs.Windows`, and `DesktopOs.MacOS` (and keep `DesktopMode` selecting `LinuxTun`/`WindowsTun`/`MacTun` vs local proxy mode without `PacServer` or OS proxy controller writes).
- Ensure active sessions always listen with `mixed` on `socksSettings.host:socksSettings.port` with user's optional credentials, both with TUN ON and TUN OFF.
- Replace the Connection Mode navigation item in `ApplicationSettingsSheet` / `desktopApp` with the TUN toggle switch (`tunEnabled`, default `true`).

### 4. Android (`sharedUI/androidMain`)

- Replace `ConnectionModeSettingsContent` with the TUN switch in `ConnectionSettingsContent` (`DEFAULT_CONNECTION_MODE = AndroidConnectionMode.Tun`).
- Remove `AdminState.configuratorVisible` gates around local proxy settings and allow blank username/password (one-time migration clears previously auto-generated hidden credentials).
- In `OlcboxVpnService`, expose `mixed` on `socksListenHost:socksListenPort` both when TUN is ON (alongside isolated internal port+login for `hev-socks5-tunnel`) and when TUN is OFF.

### 5. iOS (`sharedUI/iosMain`, `iosApp`)

- Persist `tunEnabled` (default `true`) in `IosVpnManager` and default local proxy credentials to blank (with one-time migration) on `127.0.0.1:10808`.
- Pass `socksProxySettings` and `tunEnabled` callbacks to `ApplicationSettingsSheet` in `MainViewController`.
- When `tunEnabled` is `false` inside `PacketTunnelProvider`, apply neutral `NEPacketTunnelNetworkSettings` (`includedRoutes = []`, no IPv6/DNS hijack, no `proxySettings`) and run `sing-box` `build` or `Xray`/`olcrtc` + `MixedProxyBridge.swift` without starting `hev-socks5-tunnel`. When `tunEnabled` is `true`, expose `proxy-in` (`mixed`) via `buildTun` or `MixedProxyBridge.swift` alongside `hev`/`tun-in`.

### 6. CLI (`cli/`)

- In `cli/internal/daemon/connect.go` and `handlers.go`, when connected in `tun` mode, also enable `TunProxyListen` from `proxy.Listen:proxy.Port` (`127.0.0.1:1080` default) and `TunProxyUser`/`TunProxyPass` so TUN mode simultaneously serves SOCKS5 + HTTP on the mixed inbound and reports it in `status`.
