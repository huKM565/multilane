# Пакетный бондинг (bondmux) по комнатам olcRTC в MultiLane

## Цель и ограничения

Добавить экспериментальный режим пакетного/чанкового бондинга (`bondmux`) с отдельным тумблером (по умолчанию выключен), который режет и перемешивает трафик каждого TCP/UDP-потока сразу по всем активным комнатам `olcrtc` (`4`, `8`, `12` или `16`) для суммирования пропускной способности одного соединения в режимах TUN и локального прокси, с мгновенным откатом на текущий Xray-бонд.

Обязательные ограничения:
- Полная обратная совместимость подписок (`bondVlessLink`), конфигов комнат `m1..m16` на VDS (`egress.allow: '127.0.0.1:10000'`) и старых клиентов.
- Целевой `host:port` и `UUID` всегда парсятся из `bondVlessLink` профиля (без хардкода `127.0.0.1:10000` в клиенте).
- Единая реализация транспортного ядра на Go в форке `huKM565/olcrtc` (`proofkit`) без добавления новых отдельных бинарников на платформах.
- Целевые платформы: VDS, Desktop (Linux, Windows, macOS), Android и `multilane-cli`. На iOS бондинг комнат не используется, там затрагивается только общая модель настроек `RoutingSettings`.

## Архитектура на VDS и демультиплексирование

На VDS (`48.102.174.150`) трафик всех 16 комнат `ilyukhaline-olcrtc@m1..m16` по-прежнему уходит на `127.0.0.1:10000`:

1. `ilyukhaline-xray.service` переносится с `127.0.0.1:10000` на `127.0.0.1:10002` с сохранением текущего UUID и конфигурации inbound.
2. Поднимается `ilyukhaline-bondmux.service` (`/opt/ilyukhaline/bin/olcrtc -bondmux-server /etc/ilyukhaline/bondmux.yaml`) на `127.0.0.1:10000` с параметром `fallback: 127.0.0.1:10002` и списком разрешённых UUID из Xray. В отличие от покомнатных юнитов (`GOMAXPROCS=1`), сервис `bondmux` использует все доступные ядра CPU, `LimitNOFILE=65535` и `RestartSec=2s`.
3. При каждом входящем TCP-подключении на `127.0.0.1:10000` сервер читает первые 4 байта с таймаутом `5 s`:
   - если префикс равен `MLB1` (`0x4D 0x4C 0x42 0x31`), соединение становится транспортным каналом (pipe) сессии `bondmux`;
   - иначе (VLESS начинается с байта версии `0x00`) сервер открывает соединение на `127.0.0.1:10002`, отправляет прочитанные 4 байта и прозрачно проксирует поток в Xray.
4. Все исходящие TCP- и UDP-соединения сервера `bondmux` разрешают доменные имена (`ATYP=0x03`) на стороне VDS (без локального DNS на клиенте, чтобы исключить утечки и блокировки ISP) и проверяют все полученные IP по списку `blockedRanges` аналогично `olcrtc/internal/server/egress.go` (запрет loopback, RFC 1918, CGNAT, link-local, metadata, multicast).

## Протокол `bondmux` (`pkg/bondmux`)

### 1. Хендшейк канала (pipe), коррекция часов и управление сессиями

Все комнаты одного клиентского подключения (от 1 до 16) открывают независимые SOCKS5-подключения к целевому `host:port` из `bondVlessLink` через свои локальные `olcrtc`-порты и делят общий `session_id`.

Клиент при открытии каждого pipe отправляет 62-байтный заголовок:
- `magic` (`4` байта): `MLB1` (`0x4D 0x4C 0x42 0x31`)
- `session_id` (`16` байт): криптографически случайный идентификатор сессии клиента
- `pipe_id` (`2` байта, uint16 BE): уникальный идентификатор канала внутри сессии
- `timestamp` (`8` байт, int64 BE): Unix-время в секундах с учётом локального смещения `clockOffset` (окно сервера `±120 s`)
- `hmac` (`32` байта): `HMAC-SHA256(uuid_bytes[16], magic || session_id || pipe_id || timestamp)`

Ответ сервера:
- `version` (`0x01`) + `status` (`0x00` OK или `0x01` ошибка HMAC) — `2` байта;
- при `status = 0x02` (рассинхрон часов `> 120 s`, например при Windows dual-boot или сбитом времени телефона, либо неверная версия) сервер дописывает `server_time` (`8` байт, int64 BE, итого `10` байт) — клиент вычисляет `clockOffset = server_time - now` и автоматически повторяет хендшейк один раз.

Если комната обрывается и переподключается, коннектор поднимает новый pipe с тем же `session_id` и свежим `pipe_id` без разрыва активных TCP-потоков. Сервер удерживает сессию без активных pipe в течение grace-периода `60 s`, после чего закрывает её потоки и освобождает буферы.

### 2. Фрейминг и приоритеты очереди на pipe

После хендшейка внутри pipe передаются кадры с фиксированным 20-байтным заголовком (`type[1]`, `flags[1]`, `payload_len[2]` uint16 BE до `16 KiB`, `stream_id[4]` uint32 BE, `offset[8]` uint64 BE, `chunk_id[4]` uint32 BE):
- `0x01 OPEN_TCP` (надёжный, `chunk_id > 0`, идемпотентный по `stream_id`): payload несёт `initial_window[4]` (uint32 BE) + SOCKS5-адрес (`ATYP` `0x01`/`0x03`/`0x04` + адрес + `port[2]`).
- `0x02 OPEN_OK` (надёжный, `chunk_id > 0`, идемпотентный по `stream_id`): `flags=0x00` успех / `0x01` ошибка dial; payload несёт `initial_window[4]` сервера + текст ошибки при отказе.
- `0x03 DATA` (надёжный, `chunk_id > 0`): чанк потока по смещению `offset` (размер по умолчанию `8 KiB`).
- `0x04 ACK` (ненадёжный): батч до 64 подтверждённых `chunk_id[4]`; отправляется каждые `10 ms` или каждые 4 надёжных кадра строго по живому pipe с минимальным `ewmaRTT`.
- `0x05 WINDOW`: обновляет `max_recv_offset` (`offset`); отправляется при освобождении `>= 25%` окна потока, а также дублируется каждые `250 ms` при заполненном окне или смене активных pipe, чтобы потеря одного `WINDOW` не вызывала вечный дедлок.
- `0x06 FIN` (надёжный, `chunk_id > 0`): полузакрытие направления записи (`offset = final_offset`); приёмник после выдачи байтов до `final_offset` вызывает `CloseWrite()` на TCP-сокете, сохраняя встречное чтение открытым до встречного `FIN` или `RST`.
- `0x07 RST`: немедленное закрытие обоих направлений потока и освобождение буферов.
- `0x08 UDP_PKT`: датаграмма для `flow_id = stream_id` (`ATYP` + адрес + `port[2]` + payload) без пересборки и ретрансмитов.
- `0x09 PING` / `0x0A PONG`: каждые `2 s` по каждому живому pipe (`offset = nonce`).

Каждый pipe-писатель обслуживает **две очереди с приоритетом**: высокоприоритетная очередь (`PING`, `PONG`, `ACK`, `OPEN_TCP`, `OPEN_OK`, `WINDOW`, `FIN`, `RST`) всегда вычитывается раньше очереди `DATA` / `UDP_PKT`, чтобы 8-килобайтные чанки не раздували RTT управляющих кадров под нагрузкой.

### 3. Планировщик распределения чанков

Планировщик работает симметрично на клиенте и на сервере:
- Для каждого живого pipe отслеживаются `ewmaRTT` (по `PING/PONG` и `ACK`), `inFlightBytes` (уменьшается при приходе `ACK` независимо от того, через какой pipe вернулся `ACK`) и оценка скорости `estBW`.
- Лимит `cap` на один pipe стартует с `256 KiB` и адаптивно масштабируется до `1 MiB` по BDP.
- Оценка времени доставки: `score(p) = ewmaRTT(p) + inFlightBytes(p) / estBW(p)`.
- Среди здоровых pipe со свободным местом (`inFlightBytes(p) < cap(p)`) и `score(p) <= 1.5 * bestScore` следующий чанк выбирается взвешенно-случайно с весом `1 / score(p)` (до первых замеров — равномерно случайно).

### 4. Пересборка, лимиты памяти и защита от HoL-блокировки

- Буфер пересборки упорядочивает данные по `offset` с дедупликацией; повторно пришедшие диапазоны отбрасываются с повторной отправкой `ACK`.
- Начальное окно потока (`initial_window`, передаваемое в `OPEN_TCP`/`OPEN_OK`) составляет `4 MiB` на Desktop/CLI/VDS и `1 MiB` на Android. Дополнительно вводится общий лимит буфера пересборки на всю сессию (`32 MiB` на Desktop/CLI/VDS, `12 MiB` на Android) и лимит одновременных потоков (`512` на Desktop/CLI/VDS, `256` на Android): кадры за пределами окна потока или лимита сессии отбрасываются без `ACK`, останавливая отправителя по окну без риска OOM.
- **Спасение зависших кадров (straggler retransmit)**: любой надёжный кадр (`OPEN_TCP`, `OPEN_OK`, `DATA`, `FIN`), не получивший `ACK` за `max(3 * medianRTT, 450 ms)` либо оказавшийся на закрывшемся pipe, немедленно дублируется через другой здоровый pipe (до `5` попыток). При `0` живых pipe поток ждёт появления канала до `15 s`, после чего закрывается через `RST`.

### 5. Поддержка UDP (`UDP ASSOCIATE`)

Локальный SOCKS5 клиентского `bondmux` поддерживает TCP `CONNECT` и `UDP ASSOCIATE`: датаграммы уходят кадрами `UDP_PKT` с пробросом доменных имён (`ATYP=0x03`), `idle`-таймаутом UDP-сокетов на сервере `60 s` и проверкой `blockedRanges`.

## Интеграция в MultiLane и `olcrtc`

### 1. Настройка и пользовательский интерфейс

- В `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/RoutingSettings.kt` добавляется `@SerialName("bond_packet_mux") val bondPacketMux: Boolean = false`. Поскольку `HomeScreenModel.updateRoutingSettings` и `LineSupervision.changeOfLine` сравнивают `RoutingSettings` целиком, переключение тумблера автоматически переподключает активный туннель.
- В `OlcrtcHandshakeRow.kt` добавляется `BondPacketMuxRow`, который выводится сразу под `OlcrtcHandshakeRow` в `ApplicationSettingsSheet.kt` (Desktop) и `AndroidAppSettingsSheets.kt` (Android) со строками `bond_packet_mux` / `bond_packet_mux_note` в `values`, `values-ru`, `values-fa`, `values-zh` (`strings.xml`).
- В `multilane-cli` добавляется флаг `--packet-bond` в командах `run` и `connect`, поле `PacketBond bool` (`yaml:"packet_bond,omitempty"`) в `store.Selection` и проброс через `ipc` в `daemon`.

### 2. Изменения в `/home/hukm/olcrtc-release` (форк `huKM565/olcrtc`) и CI пинах

- Пакет `pkg/bondmux`: протокол, клиентский SOCKS5-мультиплексор (готовность `StartClient` наступает только после открытия локального SOCKS5 **и** успешного `MLB1`-хендшейка хотя бы одного pipe), планировщик, буфер пересборки и серверный демультиплексор.
- `cmd/olcrtc`: флаги `olcrtc -bondmux-client <config.json>` (Desktop) и `olcrtc -bondmux-server <config.yaml>` (VDS).
- `mobile/bondmux.go`: gomobile-обёртка `BondMuxRuntime` (`mobile.NewBondMux()`) для Android AAR.
- Изменения пушатся в ветку `proofkit` форка `huKM565/olcrtc`; обновляются `replace` в `cli/go.mod` и шаг checkout `olcrtc` в `.github/workflows/multilane-windows.yml` (репозиторий `huKM565/olcrtc`, новый коммит, удаление повторного `git apply olcrtc-room-occupancy.patch`).

### 3. Адаптация платформ, watchdog и инварианты тестов

На всех платформах `sing-box` остаётся спереди на внутреннем порту `xrayPort`:
- **Desktop (`DesktopVpnManager.kt` + `DesktopBondMuxController.kt`)**: при `bondPacketMux == true` пропускается стартовый `HttpBondRoomProbe` и запускается `olcrtc -bondmux-client` со всеми `roomPorts` вместо `xrayCore`; вводится `isBondCarrierRunning()` (`bondMuxCore` либо `xrayCore`), который заменяет прямые вызовы `xrayCore.isRunning()` в проверках живости, watchdog и диагностике; `rebalanceDesktopBond` не перезапускает `sing-box` при изменении числа живых комнат; ветка `else { ... xrayCore.start(...) }` сохраняет порядок строк для `DesktopBondIntegrationTest`.
- **Android (`OlcboxVpnService.kt`)**: при `bondPacketMux == true` запускается `mobile.NewBondMux()` на `xrayPort` (окно потока `1 MiB`, лимит сессии `12 MiB`, локальный прокси строго на `127.0.0.1` со сгенерированными учётными данными) со всеми `bondRoomPorts`; вводится `isBondCarrierRunning()` для `alive`, `refreshMobileBond`, диагностики и `isTransportAlive`; `refreshMobileBond` не перезапускает `sing-box` при активном `bondMux`; сохраняются инварианты `OlcboxVpnServiceNativeRunTest`.
- **CLI (`cli/internal/engine/bond/`)**: при `PacketBond == true` вместо `xray.StartBond` создаётся in-process адаптер `bondmuxEngine` над `bondmux.Client`, реализующий `xrayEngine`.

### 4. Быстрая диагностика ошибок и откат

- Так как `StartClient` ждёт первого подтверждённого `MLB1` pipe, при необновлённом сервере VDS или неверном UUID старт немедленно завершается явной ошибкой в логе до запуска `TunnelVerifier` (без тихого таймаута).
- Выключение экспериментального тумблера сразу переводит туннель обратно на `sing-box -> Xray -> olcrtc`.

## Тестирование и верификация

1. **Юнит- и хаос-тесты `olcrtc/pkg/bondmux`**:
   - разный RTT, джиттер и приоритет `ACK`/`PING` над потоком `DATA`;
   - суммирование скорости одного TCP-потока на нескольких rate-limited каналах;
   - ретрансмит как `DATA`, так и потерянных `OPEN_TCP` / `FIN`, корректность полузакрытия `CloseWrite`;
   - обрыв и возврат комнаты посреди передачи без обрыва клиентского TCP;
   - соблюдение `initial_window` (`1 MiB`), лимита памяти сессии и периодического `WINDOW` refresh;
   - проброс домена `ATYP=0x03` без клиентского DNS и прохождение `UDP_PKT`;
   - автокоррекция сдвига часов (`status=0x02` + `server_time`), отказ при неверном UUID, прозрачный VLESS fallback на `127.0.0.1:10002`, блокировка `blockedRanges`.
2. **Тесты MultiLane**:
   - `RoutingSettingsTest` и `LineSupervisionTest` (`bondPacketMux`);
   - `OlcboxVpnServiceNativeRunTest` и `DesktopBondIntegrationTest` (обе ветки: `bondMux` и `xrayCore`, проверки `isBondCarrierRunning()`);
   - тесты `cli/internal/engine/bond` и `cli/internal/daemon`.
3. **Живой прогон на Польше (`48.102.174.150`, 16 комнат)**:
   - проверка старого Xray-режима через демукс `10000 -> 10002`;
   - замер однопоточной скорости с включённым `bondmux` в TUN и прокси-режимах;
   - остановка 2–3 сервисов комнат на VDS во время активного скачивания с проверкой непрерывности соединения.
