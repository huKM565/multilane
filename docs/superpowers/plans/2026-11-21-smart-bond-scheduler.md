# Adaptive Bond Room Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Распределять новые соединения bonded-профилей через до четырёх лучших комнат по измеренной пропускной способности, RTT и стабильности вместо одинакового roundRobin.

**Architecture:** Общий Kotlin planner измеряет активные комнаты через их SOCKS и создаёт один `BondCorePlan`, который связывает compacted room ports с вычисленными costs. Xray использует `leastLoad` и `observatory` для живого выбора без периодического перезапуска; Android и desktop хранят весь предыдущий plan для безопасного rollback.

**Tech Stack:** Kotlin 2.4.10 Multiplatform, kotlinx-coroutines 1.11.0, Ktor 3.6.0, kotlinx-serialization JSON, Xray 26.7.11, существующие Gradle common/JVM tests.

**Spec:** `docs/superpowers/specs/2026-11-20-smart-bond-scheduler-design.md`

## Global Constraints

- Новые внешние зависимости не добавляются; используются существующие Ktor, coroutines и `createProxyHttpClient`.
- Probe URL: `https://speed.cloudflare.com/__down?bytes=262144`; две попытки на комнату, timeout 5 секунд, одновременно не более четырёх probes.
- Observatory: `https://connectivitycheck.gstatic.com/generate_204`, interval `15s`, concurrency включён.
- `leastLoad`: `expected = min(4, activeRoomCount)`, `maxRTT = 2s`, `tolerance = 0.20`, `baselines = [500ms]`.
- Стоимость: `medianThroughput / roomThroughput`, ограничение 0.5–4.0; единичный failed probe получает 4.0, полный probe failure даёт всем 1.0.
- Полный 12x цикл использует не более 6 МиБ; фоновых speedtest по таймеру и периодических рестартов Xray нет.
- Один TCP/UDP flow остаётся на одной комнате; Mux, XUDP, UDP/443, авторизация, Russia-direct и sing-box front не меняются.
- Серверы, подписка, olcRTC wire protocol и роутер не меняются.
- Старый вызов `buildVlessBond` без adaptive-параметра сохраняет `roundRobin` и прежний JSON.
- Каждый кодовый task идёт через failing test, реализацию, passing test и отдельный conventional commit.

## Review Focus

- Невалидные samples и неправильные active indexes: NaN/Infinity/zero/negative throughput не должны создать битую cost; duplicate/out-of-range indexes отклоняются. Покрывается Task 1.
- Частичный или полный probe failure: одна failed комната получает 4.0, полный failure оставляет работающий пул с costs 1.0. Покрывается Tasks 1 и 2.
- Отмена или stalled HTTP: CancellationException не превращается в dead room, все созданные клиенты закрываются, Xray не перезапускается. Покрывается Tasks 2, 4 и 5.
- Compacted active ports и полная observatory-недоступность: costs соответствуют новым `vless-room-N`, первый активный VLESS остаётся explicit fallback. Покрывается Task 3.
- Неуспешный rebalance: восстанавливаются одновременно прежние ports и costs без нового speedtest. Покрывается Android/desktop source tests в Tasks 4 и 5.

---

### Task 1: Модель адаптивного плана и измерение активных комнат

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/BondScheduler.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/BondSchedulerTest.kt`

**Interfaces:**
- Consumes: существующие active indexes и полный список портов из `OlcRtcBond`/`DesktopOlcRtcBond`.
- Produces:
  - `data class BondRoomTarget(val index: Int, val port: Int)`
  - `data class BondRoomSample(val index: Int, val latencyMillis: Long, val bytesPerSecond: Double)`
  - `data class BondScheduling(val expected: Int, val costs: List<Double>)`
  - `data class BondCorePlan(val roomPorts: List<Int>, val scheduling: BondScheduling)`
  - `interface BondRoomProbe { suspend fun measure(target: BondRoomTarget): BondRoomSample? }`
  - `suspend fun measureBondRooms(targets: List<BondRoomTarget>, probe: BondRoomProbe): List<BondRoomSample?>`
  - `suspend fun planAdaptiveBondCore(activeRooms: List<Int>, allPorts: List<Int>, probe: BondRoomProbe): BondCorePlan`
  - `fun bondScheduling(samples: List<BondRoomSample?>): BondScheduling`
  - `internal fun median(values: List<Double>): Double` для непустого списка.

- [ ] **Step 1: Написать failing tests стоимости и expected**

`bondScheduling` проверяется на пулах из 1, 4, 8 и 12 комнат: `expected` равен 1, 4, 4 и 4. Быстрая комната получает меньшую cost, медленная большую; значения ограничены 0.5–4.0. Полный список null даёт всем 1.0. При частичном failure null получает 4.0. NaN, positive/negative Infinity, zero и negative throughput считаются отсутствующим измерением. Пустой список даёт `BondScheduling(expected = 0, costs = emptyList())`.

- [ ] **Step 2: Запустить tests и убедиться в RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.BondSchedulerTest`
Expected: compilation FAIL, `BondScheduling` и `bondScheduling` отсутствуют.

- [ ] **Step 3: Реализовать типы и чистый расчёт**

В `BondScheduler.kt` добавить интерфейсы из Produces. `median` сортирует значения и для чётного количества возвращает среднее двух центральных. `bondScheduling` фильтрует только конечную положительную скорость; median считается по успешным samples. Формула и fallback берутся из Global Constraints.

- [ ] **Step 4: Запустить focused test**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.BondSchedulerTest`
Expected: PASS.

- [ ] **Step 5: Добавить failing tests concurrency и compacted plan**

Fake probe с блокирующими `CompletableDeferred` доказывает, что одновременно выполняются не более четырёх измерений, результат сохраняет порядок targets, null одной комнаты не отменяет остальные. `planAdaptiveBondCore(listOf(0, 2), listOf(21081, 21082, 21083), probe)` возвращает порты `21081, 21083` и costs именно samples 0 и 2. Empty activeRooms, duplicate active indexes и out-of-range index дают `IllegalArgumentException`; duplicate target ports отклоняются.

- [ ] **Step 6: Запустить и убедиться в RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.BondSchedulerTest`
Expected: compilation FAIL, planner и runner отсутствуют.

- [ ] **Step 7: Реализовать runner и planner**

`measureBondRooms` использует `coroutineScope`, `async`, `awaitAll` и `Semaphore(4)`. `planAdaptiveBondCore` валидирует nonempty distinct sorted active indexes, границы `allPorts` и distinct ports, затем создаёт targets с исходными index и передаёт samples в `bondScheduling` в порядке activeRooms.

- [ ] **Step 8: Запустить tests и commit**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.BondSchedulerTest`
Expected: PASS, включая ограничение concurrency и все invalid inputs.

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/BondScheduler.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/BondSchedulerTest.kt
git commit -m "feat: add adaptive bond scheduling model"
```

### Task 2: HTTP speed probe через SOCKS каждой комнаты

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/BondRoomProbe.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/BondRoomProbeTest.kt`

**Interfaces:**
- Consumes: `BondRoomTarget`, `BondRoomSample`, `BondRoomProbe` и `median` из Task 1.
- Produces:
  - `internal const val BOND_PROBE_URL = "https://speed.cloudflare.com/__down?bytes=262144"`
  - `internal data class BondTransferMeasurement(val latencyMillis: Long, val bytesPerSecond: Double)`
  - `internal suspend fun measureBondDownload(client: HttpClient, url: String): BondTransferMeasurement?`
  - `internal class HttpBondRoomProbe(...) : BondRoomProbe` с параметрами `username`, `password`, `attempts = 2`, `timeoutMillis = 5_000`, `probeUrl = BOND_PROBE_URL` и тестируемой `clientFactory`.
  - `clientFactory: (SubscriptionFetchProxy, Long, Long, Long) -> HttpClient`, по умолчанию вызывающая существующий `createProxyHttpClient` с connect/request/socket timeout.

- [ ] **Step 1: Написать failing tests HTTP-результата**

С использованием `MockEngine` и существующего шаблона `runTest { withContext(Dispatchers.Default) { ... } }`:

- 200 OK и ровно 262144 байта дают конечную положительную скорость и nonnegative latency;
- 204, 500 и ответ неправильной длины дают null;
- сетевой Exception даёт null;
- CancellationException пробрасывается;
- `MockEngine { awaitCancellation() }` с timeout 50 ms завершается null по реальному deadline.

- [ ] **Step 2: Запустить и убедиться в RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.BondRoomProbeTest`
Expected: compilation FAIL, `measureBondDownload` отсутствует.

- [ ] **Step 3: Реализовать одну попытку**

В `measureBondDownload` засечь monotonic time перед `client.get`, latency сразу после HttpResponse, затем прочитать `bodyAsBytes()`. Принимать только HTTP 2xx и ровно 262144 байта. Скорость считать по времени чтения тела; нулевую измеренную длительность ограничить 1 ms, чтобы не получить Infinity. Обычные ошибки возвращают null, CancellationException пробрасывается. Переданный client здесь не закрывается: владельцем остаётся `HttpBondRoomProbe`.

- [ ] **Step 4: Запустить focused test**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.BondRoomProbeTest`
Expected: PASS.

- [ ] **Step 5: Добавить failing test двух попыток, медианы и cleanup**

`clientFactory` возвращает два MockEngine-клиента с точным телом. Проверить два запроса и возврат `BondRoomSample` с исходным target.index. Оба созданных клиента после `measure` закрыты. При CancellationException первого клиента вторая попытка не создаётся, первый клиент закрыт. Одна failed и одна successful попытка используют successful результат; две failed дают null.

- [ ] **Step 6: Запустить и убедиться в RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.BondRoomProbeTest`
Expected: compilation FAIL, `HttpBondRoomProbe` отсутствует.

- [ ] **Step 7: Реализовать `HttpBondRoomProbe`**

Для каждой попытки создавать отдельный client через factory, выполнять `withProxyAuthentication` и закрывать client в `finally`. Собрать успешные попытки, вернуть null при их отсутствии; иначе вернуть sample с медианой latency и throughput. CancellationException сразу прекращает цикл. Никаких credentials или room URLs не логировать.

- [ ] **Step 8: Запустить tests и commit**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.BondRoomProbeTest --tests org.olcbox.app.net.BondSchedulerTest`
Expected: PASS.

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/BondRoomProbe.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/BondRoomProbeTest.kt
git commit -m "feat: measure bonded room throughput"
```

### Task 3: Адаптивный Xray leastLoad и observatory

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/XrayConfig.kt:63-168`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/XrayConfigBondTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/XrayConfigDumpTest.kt`

**Interfaces:**
- Consumes: `BondScheduling` из Task 1.
- Produces: последний необязательный параметр `buildVlessBond(..., login: SocksLogin? = null, adaptive: BondScheduling? = null)`. Все старые вызовы без него остаются source-compatible.

- [ ] **Step 1: Написать failing JSON tests**

Сохранить текущие roundRobin assertions как regression. Для adaptive 12x проверить:

- `strategy.type = leastLoad`, `expected = 4`, `maxRTT = 2s`, `tolerance = 0.20`, `baselines = [500ms]`;
- costs точно соответствуют тегам и `BondScheduling.costs`;
- `fallbackTag = vless-room-1`;
- observatory содержит все 12 VLESS-тегов, pinned probe URL, `15s` и `enableConcurrency = true`;
- Mux, XUDP, dialerProxy, SOCKS credentials и UDP не изменились.

Для трёх оставшихся комнат `expected = 3`, costs идут в compacted порядке, fallback остаётся первым активным VLESS. Несовпадение costs.size и roomPorts.size, wrong expected и empty adaptive costs отклоняются. Без adaptive observatory отсутствует, стратегия остаётся roundRobin.

- [ ] **Step 2: Запустить и убедиться в RED**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.XrayConfigBondTest`
Expected: compilation FAIL, параметр `adaptive` отсутствует.

- [ ] **Step 3: Реализовать adaptive JSON**

Добавить параметр после `login`. При null выполнять текущий builder без изменений. При non-null валидировать `expected == minOf(4, roomPorts.size)`, `costs.size == roomPorts.size` и конечные положительные costs; записать leastLoad settings, costs, fallback первого активного VLESS и top-level observatory. Selector продолжает содержать все активные комнаты.

- [ ] **Step 4: Запустить tests**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.XrayConfigBondTest`
Expected: PASS, включая прежние roundRobin tests.

- [ ] **Step 5: Добавить dump и проверить реальным pinned Xray**

В `XrayConfigDumpTest` создать `vless-multi-x12-adaptive.json` с 12 портами и costs от 0.5 до 4.0. Запустить focused dump, затем:

```bash
"${XRAY_BIN:?set XRAY_BIN to the existing pinned binary}" version
XRAY_BIN="${XRAY_BIN}" ./scripts/check-xray-configs.sh
```

Expected: version именно 26.7.11; каждый dumped JSON, включая adaptive, проходит `xray run -test`.

- [ ] **Step 6: Commit**

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/XrayConfig.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/XrayConfigBondTest.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/XrayConfigDumpTest.kt
git commit -m "feat: select stable bonded rooms with xray"
```

### Task 4: Интеграция adaptive plan в Android

**Files:**
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt:1257-1567,2005-2052,2260-2273`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/OlcboxVpnServiceNativeRunTest.kt`

**Interfaces:**
- Consumes: `BondCorePlan`, `planAdaptiveBondCore`, `HttpBondRoomProbe` из Tasks 1–2; adaptive-параметр Xray из Task 3.
- Produces: `startCore(..., bondCorePlan: BondCorePlan? = null)` вместо `bondRoomPorts`; `bondBalancedPlan: BondCorePlan?` хранит план текущего working core.

- [ ] **Step 1: Перевести source tests на новый контракт и убедиться в RED**

В `OlcboxVpnServiceNativeRunTest` проверить:

- `startMobileBond` вызывает `planAdaptiveBondCore` после первого active set и до `startCore`;
- Xray получает `roomPorts = bondCorePlan.roomPorts` и `adaptive = bondCorePlan.scheduling`;
- `rebalanceMobileBond` использует новый plan и при failure восстанавливает `previousPlan` без второго вызова planner;
- cancellation проверяется после измерения;
- cleanup очищает `bondBalancedPlan`.

Обновить старый test `bondRebalanceRestoresTheLastWorkingCoreAfterReplacementFailure`: он должен требовать сохранение целого plan, а не только ports.

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.OlcboxVpnServiceNativeRunTest`
Expected: FAIL, planner и `bondCorePlan` отсутствуют.

- [ ] **Step 2: Интегрировать первый запуск**

После `bond.start` и проверки generation построить `BondCorePlan` через `planAdaptiveBondCore(activeRooms, ports, HttpBondRoomProbe(socksUsername, socksPassword))`. Выполнить `coroutineContext.ensureActive()` после измерения. Передать plan в `startCore`; после успешного старта сохранить его в `bondBalancedPlan`. Не запускать probes из watchdog при неизменном active set.

- [ ] **Step 3: Интегрировать startCore, rebalance и cleanup**

Заменить nullable ports на nullable plan во всех bonded вызовах. В Xray builder передавать оба поля plan. В `rebalanceMobileBond` один раз измерить новый active set; сохранить `previousPlan`; после успеха обновить `bondBalancedRooms` и `bondBalancedPlan`, после failure восстановить previousPlan без измерений. При остановке очистить plan вместе с текущими ports/rooms. Обычные core calls передают null и не получают adaptive JSON.

- [ ] **Step 4: Запустить tests и Android compile**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.OlcboxVpnServiceNativeRunTest --tests org.olcbox.app.net.BondSchedulerTest --tests org.olcbox.app.net.BondRoomProbeTest :androidApp:compileGithubDebugKotlin`
Expected: BUILD SUCCESSFUL, все tests PASS.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/OlcboxVpnServiceNativeRunTest.kt
git commit -m "feat: use adaptive bond scheduling on android"
```

### Task 5: Интеграция adaptive plan в desktop

**Files:**
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt:335-337,1897-1956,2019-2091,2105-2111`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopBondIntegrationTest.kt`

**Interfaces:**
- Consumes: те же общие Tasks 1–3.
- Produces: `desktopBalancedPlan: BondCorePlan?`; локальный `startCores(plan: BondCorePlan, label: String)` в rebalance принимает неразрывно ports и scheduling.

- [ ] **Step 1: Перевести desktop source tests на новый контракт**

Проверить:

- initial start вызывает planner после первого ready set и до `xrayCore.start`;
- builder получает ports и adaptive из одного plan;
- rebalance создаёт новый plan один раз;
- local `startCores` принимает `BondCorePlan`;
- restore вызывает `startCores(previousPlan, "restored")` без повторного planner;
- cleanup очищает `desktopBalancedPlan`;
- старые Linux supervisor и пустой-active-set guards остаются.

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.desktop.DesktopBondIntegrationTest`
Expected: FAIL, новые planner и plan fields отсутствуют.

- [ ] **Step 2: Интегрировать initial start**

После формирования `desktopBalancedRooms` измерить активные комнаты через их исходные indexes и `bond.rooms[index].port`. Создать Xray из полученного plan; сохранить `desktopBalancedPlan` только после успешного запуска Xray и sing-box. Отмена пробрасывается в существующий catch/cleanup.

- [ ] **Step 3: Интегрировать rebalance и cleanup**

Перед изменением cores вычислить новый plan. Сохранить предыдущий целиком. `startCores` читает ports/scheduling только из аргумента plan; после успеха обновить balanced rooms и plan. При failure вернуть previousPlan без нового speedtest. Очистить plan при stop и error paths, где уже очищаются balanced rooms.

- [ ] **Step 4: Запустить desktop tests и compile**

Run: `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.vpn.desktop.DesktopBondIntegrationTest --tests org.olcbox.app.vpn.desktop.DesktopOlcRtcBondTest :desktopApp:compileKotlin`
Expected: BUILD SUCCESSFUL, все tests PASS.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopBondIntegrationTest.kt
git commit -m "feat: use adaptive bond scheduling on desktop"
```

### Task 6: Полная проверка, сборки и живое сравнение

**Files:**
- Modify только при найденной регрессии; product code без причины не менять.
- Artifacts: существующие build output paths, без новых report/scaffolding files в репозитории.

**Interfaces:**
- Consumes: все Tasks 1–5 и существующие Germany/Finland комнаты.
- Produces: проверенные Android APK и Linux AppImage; измеренный verdict adaptive против roundRobin.

- [ ] **Step 1: Запустить полный standard verification**

```bash
./gradlew :sharedUI:jvmTest :androidApp:compileGithubDebugKotlin :desktopApp:compileKotlin
"${XRAY_BIN:?set XRAY_BIN to the verified 26.7.11 binary}" version
XRAY_BIN="${XRAY_BIN}" ./scripts/check-xray-configs.sh
```

Expected: BUILD SUCCESSFUL, все XML test reports имеют zero failures/errors, Xray принимает все configs. `UP-TO-DATE` без fresh focused runs из Tasks 1–5 не считается доказательством. `git status --short` проверяется после сборки; удаляются только созданные ненужные временные outputs.

- [ ] **Step 2: Собрать Android APK**

```bash
ANDROID_HOME=/home/hukm/Android/Sdk ANDROID_SDK_ROOT=/home/hukm/Android/Sdk OLCRTC_REPO=/home/hukm/olcrtc-release \
  ./gradlew -Polcbox.version=1.0.472 -Polcbox.versionCode=472 :androidApp:assembleGithubRelease --stacktrace
```

Expected: BUILD SUCCESSFUL и подписанный release APK в существующем output path. Проверить имя, подпись и отсутствие изменений подписки/комнат.

- [ ] **Step 3: Собрать Linux AppImage**

Сохранить существующий пользовательский AppImage под другим именем, не перезаписывая его. Собрать candidate:

```bash
APPIMAGE_EXTRACT_AND_RUN=1 OLCRTC_REPO=/home/hukm/olcrtc-release APPIMAGETOOL="${APPIMAGETOOL:-appimagetool}" \
  ./gradlew --no-daemon -Polcbox.version=1.0.472 :desktopApp:packageReleaseLinuxAppImage --stacktrace
```

Expected: BUILD SUCCESSFUL и `IlyukhaLine-1.0.472-amd64.AppImage`. Скопировать candidate под отдельным именем с суффиксом `adaptive` в `~/Загрузки`; старый AppImage оставить для A/B. Проверить наличие pinned olcRTC, sing-box, Xray, hev и корректную архитектуру.

- [ ] **Step 4: Проверить реальную разницу между комнатами**

На candidate записать per-room latency и throughput для Mamba 12x, не выводя ключи/URL. Если устойчивой разницы нет, не выдавать scheduler за ускорение и продолжить regression checks. Если есть, убедиться, что costs соответствуют измерениям и медленная комната получает большую cost.

- [ ] **Step 5: Выполнить три alternating A/B pairs**

На одних и тех же ВДС и комнатах чередовать старый AppImage roundRobin и candidate adaptive для Mamba 8x/12x. Каждый замер использует несколько параллельных TCP downloads и uploads. Записать суммарный throughput, время новых соединений, reconnects и обрывы. VK 4x прогоняется как regression, UDP DNS/XUDP и Russia-direct проверяются отдельно. Роутер не трогать.

Adaptive принимается только при измеренном росте суммарной скорости без новых ошибок и обрывов. Если разница перекрывается обычным разбросом, runtime call paths остаются на roundRobin; diagnostics и tests не выдаются за пользовательское ускорение.

- [ ] **Step 6: Проверить observatory без reconnect**

Во время candidate-сессии искусственно деградировать одну тестовую комнату без изменения остальных. Ожидание observatory: новые соединения уходят с неё, уже установленное контрольное соединение не обрывается самим observatory, ядро не перезапускается. После восстановления комната снова допускается. Android отдельно проверяет stop/reconnect без оставшихся probes и занятых портов.

- [ ] **Step 7: Финальный review и статус**

Провести один whole-branch review от последнего docs-only commit до implementation HEAD: cancellation, compacted mapping, fallback, rollback, secret logging, unchanged single-room behavior. Исправить Critical/Important через RED/GREEN. Проверить `git status --short` и перечислить измеренный live-эффект, сборки и оставшиеся ограничения. Push/merge без отдельного разрешения не выполнять.
