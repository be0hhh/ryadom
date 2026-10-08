#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
STATE="$ROOT/deploy/state"
if [[ $# != 2 ]]; then
  echo "Использование: bash deploy/setup.sh имя.tailnet.ts.net 100.x.y.z" >&2
  exit 1
fi
command -v python3 >/dev/null
python3 - "$1" "$2" <<'PY'
import ipaddress, re, sys
if not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.ts\.net', sys.argv[1]):
    raise SystemExit('Нужен полный DNSName узла Tailscale без завершающей точки')
if ipaddress.ip_address(sys.argv[2]) not in ipaddress.ip_network('100.64.0.0/10'):
    raise SystemExit('Нужен IPv4 узла WSL из диапазона Tailscale 100.64.0.0/10')
PY
# Atomic mkdir refuses existing configs, including an incomplete earlier setup.
mkdir -m 700 -- "$STATE"
python3 - "$STATE" "$1" "$2" <<'PY'
import os, pathlib, secrets, shlex, sys, urllib.parse
state, fqdn, ip = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
values = {
    'RYADOM_INSTALL_KEY': secrets.token_hex(32),
    'LIVEKIT_API_KEY': 'RY' + secrets.token_hex(16),
    'LIVEKIT_API_SECRET': secrets.token_hex(32),
    'LIVEKIT_URL': 'wss://' + fqdn,
    'LIVEKIT_INTERNAL_URL': 'http://127.0.0.1:7880',
    'TAILSCALE_FQDN': fqdn,
    'TAILSCALE_IP': ip,
}
def write(name, value):
    with (state / name).open('x') as stream:
        os.chmod(stream.name, 0o600)
        stream.write(value)
write('server.env', ''.join(k + '=' + shlex.quote(v) + '\n' for k, v in values.items()))
write('livekit.yaml', f'''port: 7880
bind_addresses: [127.0.0.1]
rtc:
  node_ip: {ip}
  use_external_ip: false
  tcp_port: 7881
  udp_port: 7882
  interfaces:
    includes: [tailscale0]
  ips:
    includes: [{ip}/32]
room:
  max_participants: 2
turn:
  enabled: false
keys:
  {values['LIVEKIT_API_KEY']}: {values['LIVEKIT_API_SECRET']}
logging:
  level: info
''')
uri = 'ryadom://setup?' + urllib.parse.urlencode({
    'server': 'https://' + fqdn + ':8443', 'key': values['RYADOM_INSTALL_KEY']})
write('bootstrap-uri.txt', uri + '\n')
print('Сохранено в deploy/state (700; файлы600). Приватная настройка приложения:')
print(uri)
print('Это ключ создания комнат. Передавайте только доверенным участникам, не публикуйте.')
PY
