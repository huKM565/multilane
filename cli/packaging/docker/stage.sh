#!/bin/sh
set -eu
dist="$1"; version="$2"
for pair in "amd64 linux/amd64" "arm64 linux/arm64" "arm7 linux/arm/v7"; do
  arch="${pair%% *}"; platform="${pair#* }"
  tgz="$dist/multilane-cli-$version-linux-$arch.tar.gz"
  [ -f "$tgz" ] || { echo "stage.sh: $tgz missing (arch $arch)" >&2; exit 1; }
  out="$dist/docker/$platform"
  rm -rf "$out"; mkdir -p "$out"
  tar -C "$out" -xzf "$tgz" ./multilane-cli
  chmod 0755 "$out/multilane-cli"
done
echo "staged: $(ls -d "$dist"/docker/linux/* "$dist"/docker/linux/arm/* 2>/dev/null | tr '\n' ' ')"
