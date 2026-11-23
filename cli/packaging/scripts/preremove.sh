#!/bin/sh
set -e
case "${1:-}" in
  1|upgrade|failed-upgrade) exit 0 ;;
esac
systemd_dir="${MULTILANE_SYSTEMD_DIR:-${GHOSTLANE_SYSTEMD_DIR:-/run/systemd/system}}"
openrc_dir="${MULTILANE_OPENRC_DIR:-${GHOSTLANE_OPENRC_DIR:-/run/openrc}}"
if [ -d "$systemd_dir" ] && command -v systemctl >/dev/null 2>&1; then
  systemctl stop multilane-cli.service >/dev/null 2>&1 || true
  systemctl disable multilane-cli.service >/dev/null 2>&1 || true
elif [ -d "$openrc_dir" ] && command -v rc-update >/dev/null 2>&1; then
  rc-service multilane-cli stop >/dev/null 2>&1 || true
  rc-update del multilane-cli default >/dev/null 2>&1 || true
fi
