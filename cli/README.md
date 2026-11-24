# multilane-cli — MultiLane for Linux servers and routers

Headless MultiLane client: one static binary supporting olcRTC rooms (including bonded 4x/8x/12x multi-room pools balanced across all active rooms), VLESS Reality, Hysteria2, and XHTTP, with automatic failover, optional kill switch, direct bypass rules (`--direct-rules /etc/multilane-direct.rules`), and Podkop-style selective tunnel rules (`--tunnel-rules /etc/multilane-tunnel.rules`).

```sh
multilane-cli add 'https://…/sub/…'
multilane-cli list
multilane-cli connect DE --proxy
multilane-cli connect DE --tun
multilane-cli connect DE --tun --kill-switch
multilane-cli status
multilane-cli disconnect
```

Build locally:

```sh
make vet
make test
make build
make build-all
```
