# multilane-cli — MultiLane for Linux servers and routers

Headless MultiLane client: one static binary supporting olcRTC rooms (including bonded 4x/8x/12x multi-room pools), VLESS Reality, Hysteria2 and XHTTP, with failover and optional direct bypass rules (`--direct-rules /etc/multilane-direct.rules`).

```sh
multilane-cli add 'https://…/sub/…'
multilane-cli list
multilane-cli connect DE --proxy
multilane-cli connect DE --tun
multilane-cli status
```

Build locally:

```sh
make build
make build-all
```
