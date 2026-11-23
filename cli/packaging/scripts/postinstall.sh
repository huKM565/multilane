#!/bin/sh
set -e
systemd_dir="${MULTILANE_SYSTEMD_DIR:-${GHOSTLANE_SYSTEMD_DIR:-/run/systemd/system}}"
openrc_dir="${MULTILANE_OPENRC_DIR:-${GHOSTLANE_OPENRC_DIR:-/run/openrc}}"
init_script="${MULTILANE_INIT_SCRIPT:-${GHOSTLANE_INIT_SCRIPT:-/etc/init.d/multilane-cli}}"
next="Next: multilane-cli add <list-url>; multilane-cli connect DE --tun (or --proxy)."
if [ -d "$systemd_dir" ] && command -v systemctl >/dev/null 2>&1; then
  systemctl daemon-reload || true
  systemctl enable multilane-cli.service >/dev/null 2>&1 || true
  if systemctl is-active --quiet multilane-cli.service; then
    systemctl restart multilane-cli.service || true
  else
    systemctl start multilane-cli.service || true
  fi
  echo "multilane-cli installed and running. $next"
elif [ -d "$openrc_dir" ] && command -v rc-update >/dev/null 2>&1 && [ -f "$init_script" ]; then
  rc-update add multilane-cli default >/dev/null 2>&1 || true
  if rc-service multilane-cli status >/dev/null 2>&1; then
    rc-service multilane-cli restart || true
  else
    rc-service multilane-cli start || true
  fi
  echo "multilane-cli installed and running (OpenRC). $next"
else
  echo "multilane-cli installed. This system has no systemd or OpenRC: run the daemon yourself,"
  echo "  multilane-cli run   (as root, or as a user with CAP_NET_ADMIN for --tun)"
  echo "or wrap that command in your init system; then: multilane-cli add <list-url>; multilane-cli connect DE --proxy"
fi
