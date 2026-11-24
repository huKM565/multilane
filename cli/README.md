# multilane-cli — MultiLane для Linux-серверов и роутеров

Консольный клиент MultiLane: один статический бинарь с поддержкой комнат olcRTC (включая мультикомнатный бондинг 4x/8x/12x с выбором наименее занятых комнат через XMPP MUC и управлением активным числом комнат через `##bond-active`), VLESS Reality, Hysteria2 и XHTTP, с авто-failover, опциональным kill switch, правилами обхода (`--direct-rules`), избирательной маршрутизацией в стиле Podkop (`--tunnel-rules`) и параллельным LAN-прокси (`--proxy-listen`).

```sh
multilane-cli add 'https://…/sub/…'
multilane-cli list
multilane-cli connect PL --proxy
multilane-cli connect PL --tun
multilane-cli connect PL --tun --kill-switch
multilane-cli status
multilane-cli disconnect
```

Локальная сборка:

```sh
make vet
make test
make build
make build-all
```
