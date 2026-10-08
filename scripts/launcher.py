"""Interactive entrypoint for the existing private WSL deployment."""
import argparse
import fcntl
import hashlib
import ipaddress
import json
import os
import pwd
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent


class UserError(RuntimeError):
    pass


def node_from_status(status):
    if status.get('BackendState') != 'Running':
        raise UserError('Tailscale не подключён. Заверши вход по ссылке, затем повтори ./run.sh.')
    node = status.get('Self') or {}
    hostname = node.get('DNSName', '').rstrip('.')
    if not hostname.endswith('.ts.net') or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789.-' for c in hostname):
        raise UserError('У узла нет адреса *.ts.net. Включи MagicDNS в настройках Tailscale.')
    for raw in node.get('TailscaleIPs') or []:
        try:
            address = ipaddress.ip_address(raw)
            if address.version == 4 and address in ipaddress.ip_network('100.64.0.0/10'):
                return hostname, str(address)
        except ValueError:
            pass
    raise UserError('Не найден IPv4 Tailscale. Проверь подключение узла.')


def firewall_valid(document):
    entries = document.get('nftables', [])
    chains = [e['chain'] for e in entries if 'chain' in e]
    if len(chains) != 1:
        return False
    chain = chains[0]
    expected = {'family': 'inet', 'table': 'ryadom_media', 'name': 'input', 'type': 'filter', 'hook': 'input', 'prio': -10, 'policy': 'accept'}
    if any(chain.get(k) != v for k, v in expected.items()):
        return False
    rules = [e['rule'] for e in entries if 'rule' in e]
    if len(rules) != 2:
        return False
    remaining = {('tcp', 7881), ('udp', 7882)}
    for rule in rules:
        if any(rule.get(k) != v for k, v in {'family': 'inet', 'table': 'ryadom_media', 'chain': 'input'}.items()):
            return False
        expressions = rule.get('expr', [])
        # nft may insert a counter; it does not change the verdict/match semantics.
        expressions = [e for e in expressions if set(e) != {'counter'}]
        if len(expressions) not in (4, 5) or expressions[-1] != {'drop': None}:
            return False
        interfaces = set()
        port_match = None
        protocol_guard = None
        for expression in expressions[:-1]:
            match = expression.get('match', {})
            left = match.get('left', {})
            if left == {'meta': {'key': 'iifname'}} and match.get('op') == '!=':
                interfaces.add(match.get('right'))
            elif left == {'meta': {'key': 'l4proto'}} and match.get('op') == '==':
                protocol_guard = match.get('right')
            elif match.get('op') == '==' and left.get('payload', {}).get('field') == 'dport':
                port_match = (left['payload'].get('protocol'), match.get('right'))
            else:
                return False
        if interfaces != {'tailscale0', 'lo'} or port_match not in remaining:
            return False
        if protocol_guard is not None and protocol_guard != port_match[0]:
            return False
        remaining.remove(port_match)
    return not remaining


def needs_server_build(root):
    root = Path(root)
    jar = root / 'server/build/install/server/lib/server.jar'
    marker = root / 'server/build/install/server/.ryadom-source.sha256'
    binary = root / 'server/build/install/server/bin/server'
    if not jar.is_file() or not binary.is_file() or not marker.is_file():
        return True
    return marker.read_text().strip() != server_fingerprint(root)


def server_fingerprint(root):
    root = Path(root)
    inputs = list((root / 'server/src').rglob('*'))
    inputs += [root / name for name in ('server/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties')]
    digest = hashlib.sha256()
    for path in sorted(path for path in inputs if path.is_file()):
        digest.update(path.relative_to(root).as_posix().encode() + b'\0')
        digest.update(path.read_bytes())
        digest.update(b'\0')
    return digest.hexdigest()


def runtime_prefix(docker_group):
    if not docker_group:
        return []
    return ['sudo', '--user', pwd.getpwuid(os.getuid()).pw_name, '--group', 'docker', '--']


def deployment_command(docker_group=False):
    return runtime_prefix(docker_group) + ['env', 'RYADOM_MEDIA_FIREWALL_CONFIRMED=yes', 'bash', str(ROOT / 'deploy/start.sh')]


def ensure_operator():
    if os.geteuid() == 0:
        return
    username = pwd.getpwuid(os.getuid()).pw_name
    preferences = json.loads(admin(['tailscale', 'debug', 'prefs'], capture=True).stdout)
    operator = preferences.get('OperatorUser', '')
    if operator == username:
        return
    if operator:
        raise UserError(f'У Tailscale уже назначен другой локальный оператор ({operator}). Не заменяю его; нужна явная настройка владельцем.')
    confirm(f'Разрешить пользователю {username} управлять локальным Tailscale, чтобы запускать Serve без root?')
    admin(['tailscale', 'set', '--operator=' + username])


def command(args, *, capture=False, check=True):
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=capture)
    if check and result.returncode:
        detail = (result.stderr or '').strip() if capture else ''
        raise UserError(f'Не выполнена команда {args[0]} (код {result.returncode}). {detail}')
    return result


def admin(args, **kwargs):
    return command(([] if os.geteuid() == 0 else ['sudo']) + list(args), **kwargs)


def confirm(message):
    print(message, flush=True)
    if not sys.stdin.isatty() or input('Продолжить? [y/N] ').strip().lower() not in ('y', 'yes', 'д', 'да'):
        raise UserError('Настройка не выполнена. Запусти ./run.sh в обычном терминале и подтверди нужный шаг.')


def dependencies():
    missing = [package for executable, package in [('curl', 'curl'), ('docker', 'docker.io'), ('nft', 'nftables'), ('flock', 'util-linux')]
               if shutil.which(executable) is None]
    if missing:
        os_release = Path('/etc/os-release').read_text()
        if '\nID=ubuntu\n' not in '\n' + os_release:
            raise UserError('Установи зависимости своей ОС: ' + ', '.join(missing))
        confirm('Для запуска нужны пакеты Ubuntu: ' + ', '.join(missing) + '. Потребуется sudo.')
        admin(['apt-get', 'update'])
        admin(['apt-get', 'install', '-y', *missing])
    if shutil.which('tailscale') is None:
        confirm('Нужно установить Tailscale из официального репозитория. Это системная установка, потребуется sudo.')
        installer = ROOT / '.tools/tailscale-install.sh'
        installer.parent.mkdir(exist_ok=True)
        command(['curl', '-fL', '--retry', '2', '-o', str(installer), 'https://tailscale.com/install.sh'])
        admin(['sh', str(installer)])


def connected_node():
    # Never stop a Windows VPN silently; the operator chooses which instance to use.
    if shutil.which('powershell.exe'):
        result = command(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                          '(Get-Service -Name Tailscale -ErrorAction SilentlyContinue).Status.ToString()'], capture=True, check=False)
        if 'running' in result.stdout.lower():
            raise UserError('Tailscale уже работает на Windows. Отключи его там перед запуском WSL-варианта; скрипт сам чужой VPN не останавливает.')
    for service in ('docker', 'tailscaled'):
        if command(['systemctl', 'is-active', '--quiet', service], check=False).returncode:
            print(f'Запускаю службу {service}…', flush=True)
            admin(['systemctl', 'start', service])
    if os.environ.get('DOCKER_HOST', 'unix:///var/run/docker.sock') != 'unix:///var/run/docker.sock':
        raise UserError('DOCKER_HOST указывает не на локальный WSL Docker. Удали эту настройку из терминала для данного запуска.')
    endpoint = command(['docker', 'context', 'inspect', '--format', '{{(index .Endpoints "docker").Host}}'], capture=True).stdout.strip()
    if endpoint != 'unix:///var/run/docker.sock':
        raise UserError('Выбран не локальный WSL Docker context. Нужен Linux Engine на /var/run/docker.sock; remote/Desktop не запускаю.')
    docker_group = False
    if command(['docker', 'info', '--format', '{{.OSType}}'], capture=True, check=False).stdout.strip() != 'linux':
        if admin(['docker', '--host', 'unix:///var/run/docker.sock', 'info', '--format', '{{.OSType}}'], capture=True, check=False).stdout.strip() != 'linux':
            raise UserError('Локальный Linux Docker Engine недоступен даже через sudo. Проверь службу docker.')
        confirm('Для Docker нужен доступ к его сокету. Запустить сервер твоим пользователем с временной группой docker через sudo? Постоянное членство в группах не изменяется.')
        docker_group = True
    result = command(['tailscale', 'status', '--json'], capture=True, check=False)
    try:
        status = json.loads(result.stdout)
    except json.JSONDecodeError:
        raise UserError('Не удалось получить статус Tailscale. Проверь службу tailscaled.') from None
    if status.get('BackendState') != 'Running':
        print('Сейчас Tailscale покажет ссылку. Открой её в браузере и войди в свой аккаунт.\n'
              'На телефонах используй тот же tailnet; пароль передавать партнёру не нужно.', flush=True)
        admin(['tailscale', 'up', '--accept-dns=false'])
        status = json.loads(command(['tailscale', 'status', '--json'], capture=True).stdout)
    ensure_operator()
    hostname, address = node_from_status(status)
    return hostname, address, docker_group


def prepare_state(hostname, address):
    state = ROOT / 'deploy/state'
    if not state.exists():
        command(['bash', str(ROOT / 'deploy/setup.sh'), hostname, address], capture=True)
        # setup.sh's output contains the bootstrap key. Show it only via the explicit link step.
        print('Приватная конфигурация создана.', flush=True)
    else:
        expected = [state / name for name in ('server.env', 'livekit.yaml', 'bootstrap-uri.txt')]
        if not all(p.is_file() for p in expected):
            raise UserError('deploy/state существует, но настройка неполна. Файлы сохранены; сначала разбери незавершённую настройку.')
        env_text = (state / 'server.env').read_text()
        if f'TAILSCALE_IP={address}\n' not in env_text or f'TAILSCALE_FQDN={hostname}\n' not in env_text:
            raise UserError('Адрес Tailscale отличается от сохранённого. Настройка и ключи сохранены; нужна явная перенастройка.')


def prepare_firewall():
    result = admin(['nft', '-j', 'list', 'tables'], capture=True)
    tables = json.loads(result.stdout).get('nftables', [])
    exists = any(e.get('table', {}).get('family') == 'inet' and e.get('table', {}).get('name') == 'ryadom_media' for e in tables)
    if not exists:
        print('Добавляю отдельный фильтр media-портов Рядом (7881/TCP и 7882/UDP). Остальные правила не меняю.', flush=True)
        admin(['nft', '-f', str(ROOT / 'deploy/tailnet-only.nft')])
    document = json.loads(admin(['nft', '-j', 'list', 'table', 'inet', 'ryadom_media'], capture=True).stdout)
    if not firewall_valid(document):
        raise UserError('Таблица ryadom_media отличается от ожидаемой. Не перезаписываю её; проверь sudo nft list table inet ryadom_media.')


def prepare_server():
    if not (ROOT / '.tools/jdk/bin/java').exists() or not (ROOT / '.tools/gradle-8.13/bin/gradle').exists():
        print('Подготавливаю локальные инструменты сборки…', flush=True)
        command(['bash', str(ROOT / 'scripts/bootstrap.sh')])
    if needs_server_build(ROOT):
        print('Собираю сервер (APK не пересобирается)…', flush=True)
        fingerprint = server_fingerprint(ROOT)
        command([str(ROOT / 'gradlew'), ':server:installDist'])
        if server_fingerprint(ROOT) != fingerprint:
            raise UserError('Исходники сервера изменились во время сборки. Повтори ./run.sh после завершения правок.')
        (ROOT / 'server/build/install/server/.ryadom-source.sha256').write_text(fingerprint + '\n')


def print_link():
    path = ROOT / 'deploy/state/bootstrap-uri.txt'
    if not path.is_file():
        raise UserError('Сначала выполни ./run.sh для настройки сервера.')
    print('\nПриватная ссылка настройки — скопируй её в приложение на своём телефоне:\n' + path.read_text().strip(), flush=True)
    print('\n1. Установите dist/ryadom.apk и Tailscale на оба телефона.\n'
          '2. Подключите телефоны к своему tailnet и включите Tailscale.\n'
          '3. Ты: вставь ссылку выше в «Рядом», введи имя → «Создать комнату».\n'
          '4. «Пригласить партнёра» → отправь ссылку комнаты второму человеку.\n'
          '5. Партнёр вставляет её → «Войти по приглашению».\n'
          '6. «Показать экран» → разрешение Android → открой TikTok.\n'
          'Первую ссылку не публикуй. ПК и этот терминал должны оставаться включёнными.\n'
          'Остановка: Ctrl+C или ./run.sh stop.', flush=True)


def probe(url, *, ktor=False):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        try:
            response = opener.open(url, timeout=1)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if ktor:
                return response.status in (404, 405) and json.loads(response.read(1024)).get('error') == 'Адрес не найден.'
            return response.status in (200, 404)
    except (OSError, ValueError):
        return False


def serve_configured():
    result = command(['tailscale', 'serve', 'status', '--json'], capture=True, check=False)
    try:
        config = json.loads(result.stdout)
        return all(config.get('TCP', {}).get(str(port), {}).get('HTTPS') is True for port in (443, 8443))
    except (ValueError, AttributeError):
        return False


def start():
    dependencies()
    hostname, address, docker_group = connected_node()
    prepare_state(hostname, address)
    prepare_server()
    prepare_firewall()
    image = 'livekit/livekit-server:v1.13.7'
    docker = runtime_prefix(docker_group) + ['docker']
    if command(docker + ['image', 'inspect', image], capture=True, check=False).returncode:
        print('Первый запуск: скачиваю образ LiveKit…', flush=True)
        command(docker + ['pull', image])
    process = subprocess.Popen(deployment_command(docker_group), cwd=ROOT, start_new_session=True)
    try:
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise UserError('Запуск завершился раньше времени. Подробности: deploy/state/*.log')
            if probe('http://127.0.0.1:8080/rooms', ktor=True) and probe('http://127.0.0.1:7880/') and serve_configured():
                print('\nЛокальные API отвечают, две публикации Tailscale Serve настроены.\n'
                      'Связь и звук между телефонами ещё нужно проверить.', flush=True)
                print_link()
                result = process.wait()
                print('Процессы сервера завершились.', flush=True)
                return 0 if result in (0, 130, 143) else result
            time.sleep(0.5)
        raise UserError('Сервер не подтвердил локальную готовность за 35 секунд.\n'
                        'Проверь deploy/state/server.log, livekit.log и serve443.log / serve8443.log.\n'
                        'Если Serve просит включить HTTPS — открой указанную в его логе ссылку и повтори ./run.sh.')
    except KeyboardInterrupt:
        print('\nОстанавливаю свой сервер…', flush=True)
        return 0
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()


def main(argv=None):
    parser = argparse.ArgumentParser(description='Рядом: запуск домашнего сервера. Первый раз потребуются sudo и вход Tailscale.',
                                     epilog='./run.sh — запустить; ./run.sh stop — остановить; ./run.sh link — показать ссылку для телефона.')
    parser.add_argument('action', nargs='?', default='start', choices=['start', 'stop', 'status', 'link'])
    args = parser.parse_args(argv)
    try:
        if args.action == 'stop':
            return command(['bash', str(ROOT / 'deploy/stop.sh')], check=False).returncode
        if args.action == 'link':
            print_link(); return 0
        if args.action == 'status':
            local = probe('http://127.0.0.1:8080/rooms', ktor=True) and probe('http://127.0.0.1:7880/')
            print('Локальные API отвечают.' if local else 'Локальные API не готовы.')
            return 0 if local else 1
        with (ROOT / 'deploy/launcher.lock').open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise UserError('Запуск Рядом уже выполняется. Остановка: ./run.sh stop.') from None
            return start()
    except UserError as error:
        print('\n' + str(error), file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print('\nНастройка прервана. Повторный запуск: ./run.sh', file=sys.stderr)
        return 130
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'\nНе удалось завершить настройку: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
