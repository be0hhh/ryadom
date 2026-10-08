#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
PIDFILE="$ROOT/deploy/state/supervisor.pid"
[[ -f "$PIDFILE" ]] || { echo 'Нет PID управляемого запуска.'; exit 0; }
# Avoid signalling a reused PID after a crashed WSL session.
python3 - "$PIDFILE" <<'PY'
import os, pathlib, signal, sys
path = pathlib.Path(sys.argv[1])
pid, born = path.read_text().split()
pid = int(pid)
if pid <= 1: raise SystemExit('Некорректный PID')
try:
    proc = pathlib.Path('/proc') / str(pid)
    if proc.stat().st_uid != os.getuid(): raise SystemExit('Другой владелец процесса; остановка отменена')
    actual = (proc / 'stat').read_text().rsplit(')', 1)[1].split()[19]
    if actual != born: raise SystemExit('PID уже переиспользован; остановка отменена')
    os.kill(pid, signal.SIGTERM)
    print('Отправлен TERM своему supervisor; он остановит дочерние процессы и свой LiveKit.')
except FileNotFoundError:
    print('Supervisor уже завершён; PID-файл сохранён для разбора, чужие процессы не затронуты.')
PY
