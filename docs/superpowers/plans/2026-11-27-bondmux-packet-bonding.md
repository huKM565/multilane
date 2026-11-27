# BondMux Packet Bonding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an experimental toggleable packet/chunk-bonding layer (`bondmux`) across VDS, Desktop, Android, and `multilane-cli` that splits and shuffles single-stream TCP/UDP traffic across up to 16 `olcrtc` rooms while keeping 100% compatibility with the existing Xray connection-level bond.

**Architecture:** Implement `pkg/bondmux` in `/home/hukm/olcrtc-release` (`huKM565/olcrtc`), exposing `-bondmux-client` / `-bondmux-server` in `cmd/olcrtc` and `NewBondMux()` in `mobile` (Android AAR). On the VDS, `bondmux-server` listens on `127.0.0.1:10000`, peeks 4 bytes (`MLB1`), handles bonded pipes or splices legacy VLESS (`0x00`) to Xray moved to `127.0.0.1:10002`. On Desktop, Android, and CLI, enabling `RoutingSettings.bondPacketMux` / `--packet-bond` swaps the internal `xrayPort` carrier from Xray to `bondmux` behind `sing-box`, keeping TUN, mixed proxy, and routing rules unchanged.

**Tech Stack:** Go 1.26.5 (`olcrtc`, `multilane-cli`, `gomobile`), Kotlin Multiplatform / Compose Multiplatform (`sharedUI`, `desktopApp`, `androidApp`), systemd (Ubuntu VDS).

**Spec:** `docs/superpowers/specs/2026-11-27-bondmux-packet-bonding-design.md`

## Global Constraints

- Experimental toggle defaults to `false` everywhere (`@SerialName("bond_packet_mux") val bondPacketMux: Boolean = false`, CLI `--packet-bond` default `false`).
- Target `host:port` and 16-byte `UUID` are always parsed from `bondVlessLink` (never hardcoded in client code).
- Subscription links (`poland-16-key.txt`), VDS room configs (`egress.allow: '127.0.0.1:10000'`), and legacy clients remain 100% compatible via 4-byte `MLB1` / VLESS fallback demux to `127.0.0.1:10002`.
- Android local SOCKS settings MUST remain on loopback `127.0.0.1` with auto-generated credentials (`StreamWindow = 1 MiB`, `SessionWindow = 12 MiB`, `MaxStreams = 256`); Desktop/CLI/VDS use `StreamWindow = 4 MiB`, `SessionWindow = 32 MiB`, `MaxStreams = 512`.
- No code comments in newly written TypeScript/Rust/Vue (not touched here) and keep Go/Kotlin idiomatic and concise; GitHub release notes must be in Russian.
- Existing source-text regex tests (`DesktopBondIntegrationTest`, `OlcboxVpnServiceNativeRunTest`) must keep passing alongside new `bondPacketMux` assertions.

## Review Focus

1. **Client clock skew (`> 120 s`, e.g. Windows dual-boot / phone drift)**: Server replies `status = 0x02` + `server_time[8]`, and client computes `clockOffset` and succeeds on immediate single retry (tested in Task 1 `TestHandshakeClockSkewRetry`).
2. **Dropped room carrying `OPEN_TCP` or half-close `FIN`**: Reliable control frames (`OPEN_TCP`, `OPEN_OK`, `FIN`) retransmit on another pipe and `FIN` performs TCP half-close (`CloseWrite`) without truncating reverse download data (tested in Task 2 `TestHalfCloseAndLostOpenRetransmit`).
3. **DNS leak / ISP block on domain targets**: SOCKS5 `ATYP = 0x03` domains pass untouched inside `OPEN_TCP` / `UDP_PKT` without client-side DNS resolution and are checked against `blockedRanges` after resolution on the server (tested in Task 2 `TestDomainPassthroughAndBlockedRanges`).
4. **Unupdated VDS server when user enables experimental toggle**: If `127.0.0.1:10000` speaks plain Xray (closes/resets on `MLB1`) or rejects HMAC (`0x01`), `StartClient` fails fast with a clear `bondmux handshake` error before `sing-box`/`TunnelVerifier` hangs (tested in Task 2 `TestStartClientFailsFastOnHandshakeReject`).
5. **Android & Desktop watchdog killing active `bondmux` tunnel**: Watchdogs previously checked `xrayCore.isRunning()` directly; `isBondCarrierRunning()` keeps tunnel alive when `bondPacketMux == true` and `xrayCore` is idle (tested in Task 6 `DesktopBondIntegrationTest` & `OlcboxVpnServiceNativeRunTest`).

---

### Task 1: `pkg/bondmux` Wire Protocol, Handshake, Reorder Buffer & Scheduler

**Files:**
- Create: `/home/hukm/olcrtc-release/pkg/bondmux/protocol.go`
- Create: `/home/hukm/olcrtc-release/pkg/bondmux/handshake.go`
- Create: `/home/hukm/olcrtc-release/pkg/bondmux/reorder.go`
- Create: `/home/hukm/olcrtc-release/pkg/bondmux/scheduler.go`
- Test: `/home/hukm/olcrtc-release/pkg/bondmux/protocol_test.go`

**Interfaces:**
- Produces:
  - Constants: `Magic = "MLB1"`, `HeaderSize = 20`, `MaxPayload = 16384`, `DefaultChunkSize = 8192`, frame types `FrameOpenTCP..FramePong`, `HandshakeSize = 62`, statuses `StatusOK`, `StatusAuthFail`, `StatusClockSkew`.
  - `ParseUUIDBytes(uuid string) ([16]byte, error)`
  - `ClientHandshake(conn net.Conn, uuid [16]byte, sessionID [16]byte, pipeID uint16, offset *atomic.Int64) error`
  - `ServerHandshake(r *bufio.Reader, w net.Conn, uuids [][16]byte, now func() time.Time) (sessionID [16]byte, pipeID uint16, isBondMux bool, prefix []byte, err error)`
  - `type ReorderBuffer` (`Push(offset uint64, data []byte) (contig [][]byte, accepted bool)`, `MarkFin(finalOffset uint64) (eof bool)`, `BufferedBytes() int`) with per-stream window and shared `*atomic.Int64` session byte counter.
  - `type Scheduler` (`RegisterPipe`, `RemovePipe`, `PickPipe(chunkLen int) (*Pipe, bool)`, `OnAck(chunkID uint32, rtt time.Duration)`, `RescueExpired(now time.Time) []InFlightFrame`).

- [ ] **Step 1: Write failing protocol, handshake, reorder, and scheduler unit tests in `/home/hukm/olcrtc-release/pkg/bondmux/protocol_test.go`**

Include tests with exact assertions:
- `TestFrameRoundTrip`: encodes/decodes all 10 frame types (`HeaderSize == 20`), rejects `payload_len > 16384`.
- `TestHandshakeOKAndRejectAndFallback`: valid UUID returns `isBondMux=true`; wrong UUID returns `StatusAuthFail`; 4 bytes starting with `0x00, 0x01, 0x02, 0x03` returns `isBondMux=false` and `prefix == []byte{0,1,2,3}` with remaining stream intact.
- `TestHandshakeClockSkewRetry`: client clock `-300s` off receives `StatusClockSkew` + `server_time`, updates `offset`, and succeeds on automatic retry; second skew still fails.
- `TestReorderDedupWindowAndSessionCap`: out-of-order chunks at offsets `8192`, `16384`, `0` yield contiguous `24576` bytes once `0` arrives; duplicate offset `8192` returns `accepted=true` with zero duplicate output; chunk exceeding `streamWindow` (`1<<20`) or `sessionCap` (`12<<20`) returns `accepted=false`; `MarkFin(24576)` triggers `eof=true` only after contiguous read reaches `24576`.
- `TestSchedulerShufflesFastPipesAndAvoidsSlowPipe`: given 3 pipes with `ewmaRTT` `20ms`, `25ms`, `200ms`, 100 picks distribute across pipes 1 and 2 (both `> 20%`) and `0` picks to pipe 3 while pipes 1–2 have free `cap`; `RescueExpired` returns unacked frames past `max(3*medianRTT, 450ms)` or from a removed pipe.

- [ ] **Step 2: Run tests to verify failure**

Run: `PATH="/home/hukm/.local/go1.26.5/bin:$PATH" go test -C /home/hukm/olcrtc-release ./pkg/bondmux/... -v`
Expected: FAIL (package/files do not exist yet).

- [ ] **Step 3: Implement `protocol.go`, `handshake.go`, `reorder.go`, and `scheduler.go`**

Implement exact 62-byte HMAC-SHA256 handshake (`±120s` window + 10-byte `StatusClockSkew` response and 1 automatic client retry), 20-byte BE frame codec, 2-queue priority pipe writer (`ctrlCh` drained before `dataCh`), `ReorderBuffer` with stream/session byte caps, and weighted-random (`1/score` within `1.5 * bestScore`) `Scheduler` with adaptive `cap` (`256 KiB..1 MiB`) and max `5` straggler retries.

- [ ] **Step 4: Run tests with race detector**

Run: `PATH="/home/hukm/.local/go1.26.5/bin:$PATH" go test -race -C /home/hukm/olcrtc-release ./pkg/bondmux/... -v`
Expected: PASS (all 5 test functions green, zero races).

---

### Task 2: `pkg/bondmux` Client SOCKS5, VDS Server Demux/Egress & Chaos Tests

**Files:**
- Create: `/home/hukm/olcrtc-release/pkg/bondmux/egress.go`
- Create: `/home/hukm/olcrtc-release/pkg/bondmux/session.go`
- Create: `/home/hukm/olcrtc-release/pkg/bondmux/client.go`
- Create: `/home/hukm/olcrtc-release/pkg/bondmux/server.go`
- Test: `/home/hukm/olcrtc-release/pkg/bondmux/chaos_test.go`

**Interfaces:**
- Consumes: Task 1 protocol, handshake, `ReorderBuffer`, `Scheduler`.
- Produces:
  - `StartClient(ctx context.Context, cfg ClientConfig) (*Client, error)` with `(*Client) ListenAddr() string`, `(*Client) Credentials() (string, string)`, `(*Client) ActivePipes() int`, `(*Client) IsRunning() bool`, `(*Client) Diagnostics() string`, `(*Client) Close() error`.
  - `StartServer(ctx context.Context, cfg ServerConfig) (*Server, error)` with `(*Server) Addr() string`, `(*Server) Close() error`.

- [ ] **Step 1: Write failing end-to-end & chaos tests in `/home/hukm/olcrtc-release/pkg/bondmux/chaos_test.go`**

Include tests with exact assertions:
- `TestSingleStreamAggregatesBandwidthAcrossRooms`: 4 mock SOCKS5 rooms each rate-limited to `1 MiB/s` (`4 MiB/s` total); transferring `8 MiB` over a single SOCKS5 TCP connection through `Client -> 4 rooms -> Server -> local TCP target` verifies SHA-256 integrity and achieves `>= 2.6 MiB/s` (`> 2.5x` a single room).
- `TestHalfCloseAndLostOpenRetransmit`: stalls pipe 0 right as `OPEN_TCP` is sent, verifies `OPEN_TCP` rescues over pipe 1; client sends `128 KiB` then calls `CloseWrite()`, server echoes `256 KiB` response after seeing client EOF, client reads full `256 KiB`.
- `TestRoomDropAndRejoinMidTransfer`: transfers `4 MiB` across 4 rooms while killing 2 rooms mid-stream and bringing 1 back; transfer completes with exact bytes and `0` errors.
- `TestWindowBackpressureAndRefresh`: Android-sized `StreamWindow = 1 << 20`, `SessionWindow = 12 << 20`, slow reader pauses `600ms` (forcing window `0` + `250ms` `WINDOW` refresh), then drains `3 MiB` cleanly without exceeding cap.
- `TestUDPAssociateAndVlessFallback`: verifies `UDP ASSOCIATE` round-trip echo AND sends raw bytes `0x00, 'v', 'l', 'e', 's', 's'` directly to `Server.Addr()`, asserting exact bidirectional splice to `FallbackAddr`.
- `TestDomainPassthroughAndBlockedRanges`: custom server resolver resolves `"test.example"` -> public IP test listener (proving client never resolved `"test.example"` locally), while `"loop.example"` -> `127.0.0.1` and literal `10.0.0.1:80` are rejected by `blockedRanges`.
- `TestStartClientFailsFastOnHandshakeReject`: pointing `RoomPorts` at a mock room forwarding to a plain-VLESS echo/EOF server or wrong-UUID `Server` makes `StartClient` return an error containing `"bondmux handshake"` in `< 2s`.

- [ ] **Step 2: Run tests to verify failure**

Run: `PATH="/home/hukm/.local/go1.26.5/bin:$PATH" go test -C /home/hukm/olcrtc-release ./pkg/bondmux/... -run TestSingleStream -v`
Expected: FAIL (`StartClient` / `StartServer` undefined).

- [ ] **Step 3: Implement `egress.go`, `session.go`, `client.go`, and `server.go`**

Implement bidirectional session engine (`OPEN_TCP`/`OPEN_OK`/`DATA`/`FIN`/`RST`/`WINDOW`/`ACK`/`PING`/`PONG`/`UDP_PKT`, `10ms`/4-chunk batched `ACK` on lowest-RTT pipe, `250ms` stalled-window refresh, `60s` server session grace and `60s` UDP idle timeout, `15s` zero-pipe stream wait), `blockedRanges` SSRF guard matching `internal/server/egress.go` (`AllowLoopbackForTest` test-only hook for local test targets), SOCKS5 client (`CONNECT` + `UDP ASSOCIATE` with `ATYP=0x03` domain pass-through, background room connectors for all `RoomPorts`, `StartClient` waiting for `>= 1` `StatusOK` pipe), and `Server` (`5s` 4-byte peek + `FallbackAddr` TCP splice).

- [ ] **Step 4: Run full `pkg/bondmux` test suite with `-race`**

Run: `PATH="/home/hukm/.local/go1.26.5/bin:$PATH" go test -race -C /home/hukm/olcrtc-release ./pkg/bondmux/... -v`
Expected: PASS (all unit + chaos tests green, zero data races).

---

### Task 3: `olcrtc` CLI Flags, Android `mobile.BondMuxRuntime`, and Fork Pin

**Files:**
- Create: `/home/hukm/olcrtc-release/cmd/olcrtc/bondmux.go`
- Create: `/home/hukm/olcrtc-release/cmd/olcrtc/bondmux_test.go`
- Modify: `/home/hukm/olcrtc-release/cmd/olcrtc/main.go:93-105`
- Create: `/home/hukm/olcrtc-release/mobile/bondmux.go`
- Create: `/home/hukm/olcrtc-release/mobile/bondmux_test.go`
- Modify: `/home/hukm/Docs/multilane/cli/go.mod:5-15`
- Modify: `/home/hukm/Docs/multilane/.github/workflows/multilane-windows.yml:54-66`

**Interfaces:**
- Consumes: `pkg/bondmux` (`StartClient`, `StartServer`, `ClientConfig`, `ServerConfig`).
- Produces:
  - CLI modes: `olcrtc -bondmux-client <config.json>` and `olcrtc -bondmux-server <config.yaml>`.
  - Gomobile API in package `mobile`: `NewBondMux() *BondMuxRuntime` with `SetListenHost(string)`, `SetListenPort(int64)`, `SetSocksCredentials(user, pass string)`, `SetTarget(addr string)`, `SetUUID(uuid string)`, `SetRoomPortsCSV(csv string)`, `SetRoomCredentials(user, pass string)`, `SetStreamWindow(bytes int64)`, `SetSessionWindow(bytes int64)`, `Start(readyTimeoutMillis int64) error`, `Stop(timeoutMillis int64) error`, `IsRunning() bool`, `Diagnostics() string` ( translates to Kotlin `Mobile.newBondMux()`).

- [ ] **Step 1: Write failing tests in `cmd/olcrtc/bondmux_test.go` and `mobile/bondmux_test.go`**

- `TestRunBondMuxServerAndClientFlags`: writes temp server YAML (`listen`, `fallback`, `uuids`, `dns`) and client JSON (`listen_addr`, `socks_user`, `socks_pass`, `target_addr`, `uuid`, `room_ports`, `room_user`, `room_pass`), verifies `runWithArgs` parses both and serves a round-trip stream.
- `TestMobileBondMuxLifecycle`: configures `NewBondMux()` with `127.0.0.1`, port, creds, CSV room ports, `1<<20` stream window, `12<<20` session window, verifies `Start` -> `IsRunning() == true` -> SOCKS5 transfer -> `Stop` -> `IsRunning() == false`, and rejects non-loopback `0.0.0.0` with empty creds just like `Runtime`.

- [ ] **Step 2: Run tests to verify failure**

Run: `PATH="/home/hukm/.local/go1.26.5/bin:$PATH" go test -C /home/hukm/olcrtc-release ./cmd/olcrtc/... ./mobile/... -run "TestRunBondMux|TestMobileBondMux" -v`
Expected: FAIL.

- [ ] **Step 3: Implement `cmd/olcrtc/bondmux.go`, `cmd/olcrtc/main.go` dispatch, and `mobile/bondmux.go`**

Dispatch `-bondmux-client <path>` and `-bondmux-server <path>` in `runWithArgs` right alongside `-pool`; implement `BondMuxRuntime` using only gomobile-compatible types (`string`, `int64`, `bool`, `error`) with rolling log ring for `Diagnostics()`.

- [ ] **Step 4: Run `olcrtc` tests, commit, push to `huKM565/olcrtc`, and update `multilane` pins**

Run:
```bash
export PATH="/home/hukm/.local/go1.26.5/bin:/home/hukm/go/bin:$PATH"
go test -race -C /home/hukm/olcrtc-release ./pkg/bondmux/... ./cmd/olcrtc/... ./mobile/...
```
Commit in `/home/hukm/olcrtc-release` on branch `proofkit` (`feat: add bondmux packet-bonding engine, CLI modes, and mobile runtime`), push to `fork` (`https://github.com/huKM565/olcrtc.git`), update `cli/go.mod` `replace github.com/openlibrecommunity/olcrtc => github.com/huKM565/olcrtc v0.0.0-<timestamp>-<shortsha>` (`go mod tidy` in `/home/hukm/Docs/multilane/cli`), and update `.github/workflows/multilane-windows.yml` to checkout `huKM565/olcrtc` at the full commit SHA (removing the already-included `git apply ../multilane/scripts/patches/olcrtc-room-occupancy.patch` step). Commit pin updates in `/home/hukm/Docs/multilane`.

---

### Task 4: MultiLane `RoutingSettings.bondPacketMux` & Experimental Toggle UI

**Files:**
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/RoutingSettings.kt:89-115`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/commonMain/composeResources/values/strings.xml:190-192`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/commonMain/composeResources/values-ru/strings.xml:190-192`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/commonMain/composeResources/values-fa/strings.xml:190-192`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/commonMain/composeResources/values-zh/strings.xml:190-192`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/components/OlcrtcHandshakeRow.kt:37-52`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/components/ApplicationSettingsSheet.kt:445-462`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/androidMain/kotlin/org/olcbox/app/ui/activities/AndroidAppSettingsSheets.kt:403-416,680-690`
- Test: `/home/hukm/Docs/multilane/sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/RoutingSettingsTest.kt`
- Test: `/home/hukm/Docs/multilane/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/LineSupervisionTest.kt`

**Interfaces:**
- Produces:
  - `RoutingSettings.bondPacketMux: Boolean = false` (`@SerialName("bond_packet_mux")`)
  - `@Composable fun BondPacketMuxRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit)`

- [ ] **Step 1: Write failing tests in `RoutingSettingsTest.kt` and `LineSupervisionTest.kt`**

Assert `Json.decodeFromString(RoutingSettings.serializer(), """{"mode":"global"}""").bondPacketMux == false`, repository round-trip of `RoutingSettings(bondPacketMux = true)`, JSON key `"bond_packet_mux"`, and `LineSupervision.changeOfLine` returning `LineChange.RoutingChanged` for `routing.copy(bondPacketMux = true)`.

- [ ] **Step 2: Run `:sharedUI:jvmTest --tests "*RoutingSettingsTest*"` to verify failure**

Run: `cd /home/hukm/Docs/multilane && ./gradlew :sharedUI:jvmTest --tests "*RoutingSettingsTest*"`
Expected: FAIL (`Unresolved reference: bondPacketMux`).

- [ ] **Step 3: Add `bondPacketMux`, string resources (`values`/`values-ru`/`values-fa`/`values-zh`), `BondPacketMuxRow`, and wire into `ApplicationSettingsSheet.kt` and `AndroidAppSettingsSheets.kt`**

Russian copy in `values-ru/strings.xml`:
- `bond_packet_mux`: `Пакетный бондинг (эксперимент)`
- `bond_packet_mux_note`: `Экспериментально. Дробит и перемешивает чанки каждого потока по всем активным комнатам для суммирования скорости одного соединения. Выключите для возврата на стандартный Xray-бонд. Изменение переподключает активный туннель.`
(Matching translations in `values`, `values-fa`, `values-zh`).

- [ ] **Step 4: Run tests and commit**

Run: `cd /home/hukm/Docs/multilane && ./gradlew :sharedUI:jvmTest --tests "*RoutingSettingsTest*" --tests "*LineSupervisionTest*"`
Expected: PASS. Commit `feat(ui): add experimental bondPacketMux setting and toggle`.

---

### Task 5: `multilane-cli` `--packet-bond` Flag & `bond.Session` Integration

**Files:**
- Modify: `/home/hukm/Docs/multilane/cli/internal/store/store.go:27-35`
- Modify: `/home/hukm/Docs/multilane/cli/internal/ipc/ipc.go`
- Modify: `/home/hukm/Docs/multilane/cli/cmd/multilane-cli/run.go:30-90`
- Modify: `/home/hukm/Docs/multilane/cli/cmd/multilane-cli/commands.go`
- Create: `/home/hukm/Docs/multilane/cli/internal/engine/bond/bondmux.go`
- Modify: `/home/hukm/Docs/multilane/cli/internal/engine/bond/bond.go:15-40,230-250`
- Modify: `/home/hukm/Docs/multilane/cli/internal/daemon/connect.go:494-515`
- Modify: `/home/hukm/Docs/multilane/cli/internal/daemon/handlers.go`
- Test: `/home/hukm/Docs/multilane/cli/internal/engine/bond/bond_test.go`
- Test: `/home/hukm/Docs/multilane/cli/internal/daemon/daemon_test.go`

**Interfaces:**
- Consumes: `github.com/openlibrecommunity/olcrtc/pkg/bondmux` (`StartClient`, `ClientConfig`), `store.Selection`.
- Produces: `bond.Params.PacketBond bool`, CLI `--packet-bond` flag on `run` and `connect`.

- [ ] **Step 1: Write failing tests in `bond_test.go` and `daemon_test.go`**

- `TestStartUsesBondMuxWhenPacketBondEnabled`: verifies `bond.Start` with `PacketBond: true` calls `startBondMux` (passing `Line.BondVless` `Addr:Port` + `UUID` and all `ports`) and does NOT call `startBondXray`; `PacketBond: false` still calls `startBondXray`.
- `TestDaemonConnectPropagatesPacketBond`: verifies `Selection{PacketBond: true}` passes `PacketBond: true` into `Deps.StartBond` and persists in `store.yaml`.

- [ ] **Step 2: Run CLI tests to verify failure**

Run: `PATH="/home/hukm/.local/go1.26.5/bin:$PATH" make -C /home/hukm/Docs/multilane/cli test`
Expected: FAIL (`PacketBond` undefined).

- [ ] **Step 3: Implement `bondmux.go` adapter (`xrayEngine`), CLI flags, IPC/store fields, and daemon wiring**

`bondmuxEngine` wraps `*bondmux.Client` implementing `SocksAddr()`, `Credentials()`, `State()` (`"running"` while `IsRunning()`), and `Stop(time.Duration)`. Target address is `net.JoinHostPort(line.Host, strconv.Itoa(line.Port))` and `UUID: line.UUID` from `*p.Line.BondVless`.

- [ ] **Step 4: Run CLI test suite and commit**

Run: `PATH="/home/hukm/.local/go1.26.5/bin:$PATH" make -C /home/hukm/Docs/multilane/cli test`
Expected: PASS. Commit `feat(cli): support --packet-bond via in-process bondmux engine`.

---

### Task 6: Desktop & Android `bondmux` Integration, Watchdogs & Native Build

**Files:**
- Create: `/home/hukm/Docs/multilane/sharedUI/src/jvmMain/kotlin/org/olcbox/app/net/DesktopBondMuxController.kt`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt`
- Modify: `/home/hukm/Docs/multilane/sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt`
- Test: `/home/hukm/Docs/multilane/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopBondIntegrationTest.kt`
- Test: `/home/hukm/Docs/multilane/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/OlcboxVpnServiceNativeRunTest.kt`

**Interfaces:**
- Consumes: `RoutingSettings.bondPacketMux` (Task 4), `olcrtc -bondmux-client <json>` & `Mobile.newBondMux()` (Task 3).
- Produces:
  - `DesktopBondMuxController` (`start(olcrtcBinary: Path, configJson: String)`, `stopNow()`, `isRunning(): Boolean`, `runningProcess(): Process?`, `exitCodeOrNull(): Int?`, `diagnostics(): String`).
  - `isBondCarrierRunning(): Boolean` in both `DesktopVpnManager` and `OlcboxVpnService`.

- [ ] **Step 1: Write failing tests in `DesktopBondIntegrationTest.kt` and `OlcboxVpnServiceNativeRunTest.kt`**

Add tests asserting:
- Desktop: `bondPacketMux` branches to `bondMuxCore.start`, `isBondCarrierRunning()` is used in `isOlcRtcTransportAlive` and `refreshDesktopBond`, `refreshDesktopBond` returns without calling `rebalanceDesktopBond` when `bondPacketMuxActive && singBoxCore.isRunning() && isBondCarrierRunning()`, and `stopCoreProcesses` stops `bondMuxCore`.
- Android: `bondCorePlan != null` branches on `routingSettings.bondPacketMux` to `startMobileBondMux` (`Mobile.newBondMux()`, `setListenHost(AndroidSocksProxySettings.DEFAULT_HOST)`, `1L shl 20` stream window, `12L shl 20` session window) vs `else` (`xrayCore.start`), `isBondCarrierRunning()` replaces hardcoded `xrayCore.isRunning()` in `alive`, `refreshMobileBond`, and `isTransportAlive`, and `stopCoreProcesses` stops `bondMuxRuntime`.

- [ ] **Step 2: Run `:sharedUI:jvmTest` to verify failure**

Run: `cd /home/hukm/Docs/multilane && ./gradlew :sharedUI:jvmTest --tests "*DesktopBondIntegrationTest*" --tests "*OlcboxVpnServiceNativeRunTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement `DesktopBondMuxController.kt`, `DesktopVpnManager.kt`, and `OlcboxVpnService.kt`**

Ensure:
- Target `host:port` and `uuid` come from `spec: OutboundSpec.Vless` (`"${spec.host}:${spec.port}"`, `spec.uuid`).
- All `paths.indices` ports (`ports` on Desktop, `bondRoomPorts` on Android) are passed to `bondmux` up front so newly ready/recovered rooms join automatically without restarting `sing-box`.
- Keep existing `else` (`xrayCore.start`) ordering verbatim so all prior `DesktopBondIntegrationTest` and `OlcboxVpnServiceNativeRunTest` assertions remain true.
- Rebuild `:sharedUI:olcrtc-bin:buildOlcrtcAndroidAar` against `OLCRTC_REPO=/home/hukm/olcrtc-release` so `Mobile.newBondMux()` links into Android.

- [ ] **Step 4: Run full JVM test suite + Android release Kotlin compilation and commit**

Run:
```bash
export PATH="/home/hukm/.local/go1.26.5/bin:/home/hukm/go/bin:$PATH"
export ANDROID_HOME="/home/hukm/Android/Sdk"
export ANDROID_NDK_HOME="/home/hukm/Android/Sdk/ndk/28.2.13676358"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export OLCRTC_REPO="/home/hukm/olcrtc-release"
cd /home/hukm/Docs/multilane && ./gradlew --rerun-tasks :sharedUI:olcrtc-bin:buildOlcrtcAndroidAar && ./gradlew :sharedUI:jvmTest :androidApp:compileGithubReleaseKotlin
```
Expected: `BUILD SUCCESSFUL`. Commit `feat: wire bondmux packet bonding on Desktop and Android`.

---

### Task 7: Poland VDS Deployment (`48.102.174.150`) & Live 16-Room Verification

**Files:**
- Remote modify on `48.102.174.150`: `/opt/ilyukhaline/bin/olcrtc` (backup `/opt/ilyukhaline/bin/olcrtc.bak` first)
- Remote modify on `48.102.174.150`: `/etc/ilyukhaline/xray.json` (`port: 10000` -> `port: 10002`)
- Remote create on `48.102.174.150`: `/etc/ilyukhaline/bondmux.yaml` and `/etc/systemd/system/ilyukhaline-bondmux.service`

**Interfaces:**
- Consumes: `olcrtc -bondmux-server` binary (Task 3), `multilane-cli` (Task 5).
- Produces: Live VDS running `ilyukhaline-bondmux` on `127.0.0.1:10000` -> fallback `127.0.0.1:10002` (`ilyukhaline-xray`), verified end-to-end in both modes.

- [ ] **Step 1: Build static `linux/amd64` `olcrtc` binary and deploy safely to `48.102.174.150`**

Build `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -C /home/hukm/olcrtc-release -o /tmp/olcrtc-linux-amd64 ./cmd/olcrtc`, upload via `proxychains4 -q scp`, back up `/opt/ilyukhaline/bin/olcrtc` -> `/opt/ilyukhaline/bin/olcrtc.bak`, move `xray.json` inbound port to `10002`, write `/etc/ilyukhaline/bondmux.yaml` (`listen: 127.0.0.1:10000`, `fallback: 127.0.0.1:10002`, `uuids: ["2b9618c0-76da-4f8d-bf1f-3270d30ff6a3"]`, `dns: "1.1.1.1:53"`), install & enable `ilyukhaline-bondmux.service` (`LimitNOFILE=65535`, `Restart=always`, `RestartSec=2s`, no `GOMAXPROCS=1`), restart `ilyukhaline-xray` and `ilyukhaline-bondmux` (plus `ilyukhaline-olcrtc@m1..m16` if binary updated).

- [ ] **Step 2: Verify VDS service health**

Run via `proxychains4 -q ssh root@48.102.174.150`: `systemctl is-active ilyukhaline-xray ilyukhaline-bondmux ilyukhaline-olcrtc@m{1..16}` and `ss -tlnp | grep -E '10000|10002'`.
Expected: all 18 units `active`, `127.0.0.1:10000` owned by `olcrtc`, `127.0.0.1:10002` owned by `xray`.

- [ ] **Step 3: Live end-to-end test on 16-room Poland (`~/.config/ilyukhaline/poland-16-key.txt`) in BOTH toggle states**

1. Start local `multilane-cli` in proxy mode with `--packet-bond=false` -> verify external IP `48.102.174.150` and measure single-stream download throughput (`curl --socks5-hostname`).
2. Restart with `--packet-bond=true` -> verify external IP `48.102.174.150`, confirm `bondmux` multi-pipe logs across rooms, measure single-stream download throughput, and run `systemctl restart ilyukhaline-olcrtc@m3 ilyukhaline-olcrtc@m7` on VDS mid-download to verify zero TCP stream interruption.
