#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
URL='https://github.com/heiher/hev-socks5-tunnel/releases/download/2.18.0/hev-socks5-tunnel.aar'
curl --fail --location --retry 3 --output app/libs/hev-socks5-tunnel.aar "$URL"
echo '15ec8ed121663b562c99caa5bb602d1009f24e5b09e733438b81988f12feaaab  app/libs/hev-socks5-tunnel.aar' | sha256sum --check
unzip -t app/libs/hev-socks5-tunnel.aar
