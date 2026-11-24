# multilane-cli — MultiLane для Linux-серверов и роутеров

Один бинарь, один сервис. Подключает линии из вашей подписки — комнаты olcRTC (одиночные или мультикомнатный пул 4x/8x/12x на Телемост, WB Stream, SaluteJazz, VK Звонки, МТС Линк и Jitsi/Mamba с выбором самых свободных комнат), VLESS Reality, Hysteria2 и XHTTP — с автоматическим переключением при сбоях.

    multilane-cli add <URL-подписки>              # подписка или одна ссылка olcrtc:// / vless:// / hy2://
    multilane-cli list                            # список линий с индексом, страной и типом
    multilane-cli connect PL --proxy              # SOCKS5 + HTTP на 127.0.0.1:1080 без прав root
    multilane-cli connect PL --tun                # системный TUN-интерфейс multilane0
    multilane-cli connect PL --tun --kill-switch  # блокировать трафик при падении туннеля
    multilane-cli status                          # состояние, активные комнаты бонда, адреса прокси
    multilane-cli disconnect

Режимы. `--proxy` поднимает только локальный прокси; `status` выводит строки `http_proxy` / `ALL_PROXY`. `--tun` создаёт `multilane0` и заворачивает трафик системы в туннель (SSH и локальные сети остаются доступны). При `--proxy-listen <host:port>` на демоне `run` смешанный SOCKS5+HTTP прокси работает параллельно с `--tun` (удобно для раздачи на роутере).

Избирательная маршрутизация. `--direct-rules` пускает перечисленные домены, IP/CIDR и `.srs` напрямую; `--tunnel-rules` включает режим в стиле Podkop — в туннель идут только перечисленные цели, всё остальное идёт напрямую.

Сервис. `systemctl status multilane-cli`, логи — `journalctl -u multilane-cli` (на Alpine: `rc-service multilane-cli status`, `/var/log/multilane-cli.log`). Состояние хранится в `/var/lib/multilane`, выбор линии переживает перезагрузку.

Подробное руководство: `docs/cli.md` в репозитории.

Лицензия: GPL-3.0-or-later. Исходный код: https://github.com/huKM565/multilane
