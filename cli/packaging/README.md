# multilane-cli — MultiLane for Linux servers and routers

One binary, one service. It connects the lines of your subscription — an
olcRTC room (single or bonded 4x/8x/12x multi-room pool over Telemost, WB
Stream, SaluteJazz, VK Calls, MTS Link, or Jitsi), VLESS Reality, Hysteria2,
or XHTTP — with failover between them, and routes the machine through the
tunnel.

    multilane-cli add <list-url>                  # subscription (plain or encrypted), or one olcrtc:// / vless:// / hy2:// line
    multilane-cli list                            # every line, with its country and carrier
    multilane-cli connect DE --proxy              # SOCKS5 + HTTP on 127.0.0.1:1080, no privileges
    multilane-cli connect DE --tun                # the whole machine through the tunnel
    multilane-cli connect DE --tun --kill-switch  # ...and refuse traffic outside it while no line is up
    multilane-cli status                          # state, line, active bond rooms, proxy lines
    multilane-cli disconnect

Modes. `--proxy` serves a local proxy only; `status` prints the `http_proxy` /
`ALL_PROXY` lines. `--tun` creates `multilane0` and routes everything through
it except private ranges and the machine's own addresses: SSH and any service
on the public IP keep working (a policy rule ahead of sing-box's keeps their
replies on the normal route). Stopping the service or `disconnect` removes the
tun and its rules: traffic flows directly again — unless `--kill-switch` was
given, which keeps the box closed while the selection stands and no line is up
(`disconnect` opens it).

Selective routing. `--direct-rules` keeps listed domains, IPs/CIDRs, and `.srs`
rule sets outside the tunnel; `--tunnel-rules` switches to selective mode where
only listed targets go into the tunnel and everything else stays direct.

Service. `systemctl status multilane-cli`, logs in `journalctl -u multilane-cli`
(on Alpine: `rc-service multilane-cli status`, `/var/log/multilane-cli.log`).
The daemon runs with `CAP_NET_ADMIN`; its files live under `/var/lib/multilane`.
The selection survives a reboot: the service reconnects at boot.

Lines. `connect DE` tries the country's lines in the list's order; a line that
does not answer within a minute, or three failed liveness probes in a row,
moves it to the next; the last working line is tried first next time.
`connect 6` names one line by its number in `list`.

Full guide: `docs/cli.md` in the repository.

Licence: GPL-3.0-or-later (the binary links sing-box). Source and issues:
https://github.com/huKM565/multilane
