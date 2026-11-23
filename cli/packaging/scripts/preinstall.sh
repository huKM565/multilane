#!/bin/sh
set -e
if ! getent group multilane >/dev/null 2>&1; then
  if command -v groupadd >/dev/null 2>&1; then groupadd -r multilane; else addgroup -S multilane; fi
fi
if ! getent passwd multilane >/dev/null 2>&1; then
  if command -v useradd >/dev/null 2>&1; then
    useradd -r -g multilane -d /var/lib/multilane -s /sbin/nologin -c "MultiLane tunnel" multilane
  else
    adduser -S -G multilane -h /var/lib/multilane -s /sbin/nologin multilane
  fi
fi
