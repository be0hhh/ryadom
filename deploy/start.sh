#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
STATE="$ROOT/deploy/state"
[[ -f "$STATE/server.env" && -f "$STATE/livekit.yaml" ]] || {
  echo 'Сначала выполните deploy/setup.sh.' >&2; exit 1;
}
[[ "$STATE" != /mnt/* ]] || { echo 'Храните проект в Linux FS WSL.' >&2; exit 1; }
[[ ${RYADOM_MEDIA_FIREWALL_CONFIRMED:-} == yes ]] || {
  echo 'Сначала настройте фильтр deploy/tailnet-only.nft; затем RYADOM_MEDIA_FIREWALL_CONFIRMED=yes bash deploy/start.sh (см. README).' >&2; exit 1;
}
for tool in docker tailscale python3 flock; do command -v "$tool" >/dev/null; done
[[ -x "$ROOT/server/build/install/server/bin/server" ]] || {
  echo 'Сначала: ./gradlew :server:installDist' >&2; exit 1;
}
exec 9>"$STATE/run.lock"
flock -n 9 || { echo 'Рядом уже запущен или останавливается.' >&2; exit 1; }
set -a
source "$STATE/server.env"
set +a
[[ $(tailscale ip -4) == "$TAILSCALE_IP" ]] || {
  echo 'IPv4 Tailscale изменился: проверьте сохранённую конфигурацию.' >&2; exit 1;
}
# Do not replace another application's Serve/Funnel configuration.
tailscale serve status --json | python3 -c '
import json, sys
c = json.load(sys.stdin)
if any(c.values()): raise SystemExit("Узел уже имеет Serve/Funnel config; нужен отдельный свободный узел.")'
[[ ! -e "$STATE/livekit.cid" ]] || {
  echo 'Есть livekit.cid: сначала разберите предыдущую остановку; чужие контейнеры не удаляются.' >&2; exit 1;
}
if [[ -d "$ROOT/.tools/jdk" ]]; then
  export JAVA_HOME="$ROOT/.tools/jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null
children=()
cleanup() {
  trap - EXIT INT TERM
  # jobs -pr lists only this shell's still-running children, avoiding stale PIDs.
  while read -r pid; do kill -TERM "$pid" 2>/dev/null || true; done < <(jobs -pr)
  if [[ -s "$STATE/livekit.cid" ]]; then
    cid=$(cat "$STATE/livekit.cid")
    # Only the exact ID created by this invocation is stopped.
    if [[ "$cid" =~ ^[a-f0-9]{64}$ ]]; then
      docker stop -t 10 "$cid" >/dev/null 2>&1 || true
      # --rm removes a stopped container. Retain cid if cleanup failed.
      if remaining=$(docker ps -aq --no-trunc --filter "id=$cid") && [[ -z "$remaining" ]]; then
        rm -f -- "$STATE/livekit.cid"
      fi
    fi
  fi
  for pid in "${children[@]}"; do wait "$pid" 2>/dev/null || true; done
  rm -f -- "$STATE/supervisor.pid"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf '%s %s\n' "$$" "$(awk '{print $22}' "/proc/$$/stat")" >"$STATE/supervisor.pid"
docker run --detach --rm --network host --cidfile "$STATE/livekit.cid" \
  --label ru.ryadom.owner=deploy-start \
  --mount "type=bind,src=$STATE/livekit.yaml,dst=/etc/livekit.yaml,readonly" \
  livekit/livekit-server:v1.13.7 --config /etc/livekit.yaml 9>&-
docker logs --follow "$(cat "$STATE/livekit.cid")" >"$STATE/livekit.log" 2>&1 9>&- &
children+=("$!")
"$ROOT/server/build/install/server/bin/server" >"$STATE/server.log" 2>&1 9>&- &
children+=("$!")
# Foreground Serve sessions disappear with their owned processes; no --bg persistence.
tailscale serve --https=443 http://127.0.0.1:7880 >"$STATE/serve443.log" 2>&1 9>&- &
children+=("$!")
tailscale serve --https=8443 http://127.0.0.1:8080 >"$STATE/serve8443.log" 2>&1 9>&- &
children+=("$!")
echo 'Процессы запущены; готовность не проверена. Логи: deploy/state/*.log'
echo "Остановка: Ctrl+C или bash $ROOT/deploy/stop.sh (PID $$)."
wait -n "${children[@]}"
