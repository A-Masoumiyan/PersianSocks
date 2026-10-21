#!/usr/bin/env bash
set -euo pipefail
fixture=/tmp/persiansocks-openvpn-peer
mkdir -p "$fixture"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/ca.key" -out "$fixture/ca.crt" -days 2 -subj '/CN=PersianSocks fixture CA' >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$fixture/server.key" -out "$fixture/server.csr" -subj '/CN=local.fixture' >/dev/null 2>&1
cat > "$fixture/server.ext" <<'CONFIG'
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
CONFIG
openssl x509 -req -in "$fixture/server.csr" -CA "$fixture/ca.crt" -CAkey "$fixture/ca.key" -CAcreateserial -out "$fixture/server.crt" -days 2 -extfile "$fixture/server.ext" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$fixture/client.key" -out "$fixture/client.csr" -subj '/CN=local.client' >/dev/null 2>&1
cat > "$fixture/client.ext" <<'CONFIG'
basicConstraints=CA:FALSE
keyUsage=digitalSignature
extendedKeyUsage=clientAuth
CONFIG
openssl x509 -req -in "$fixture/client.csr" -CA "$fixture/ca.crt" -CAkey "$fixture/ca.key" -CAcreateserial -out "$fixture/client.crt" -days 2 -extfile "$fixture/client.ext" >/dev/null 2>&1
cat > "$fixture/server.conf" <<CONFIG
port 11940
proto tcp-server
dev tun
server 10.77.0.0 255.255.255.0
topology subnet
ca $fixture/ca.crt
cert $fixture/server.crt
key $fixture/server.key
dh none
data-ciphers AES-128-GCM
keepalive 5 30
verb 3
log /tmp/android-openvpn-server.txt
CONFIG
touch /tmp/android-openvpn-server.txt
chmod 644 /tmp/android-openvpn-server.txt
sudo modprobe tun
if ! sudo openvpn --config "$fixture/server.conf" --daemon; then cat /tmp/android-openvpn-server.txt; exit 1; fi
nohup python3 -m http.server 18080 --bind 0.0.0.0 --directory "$fixture" > /tmp/android-host-peer.txt 2>&1 &
