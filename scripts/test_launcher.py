import copy
import contextlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('launcher', Path(__file__).with_name('launcher.py'))
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)
PROJECT = Path(__file__).resolve().parent.parent


def firewall_document():
    def rule(protocol, port):
        return {'rule': {'family': 'inet', 'table': 'ryadom_media', 'chain': 'input', 'expr': [
            {'match': {'op': '!=', 'left': {'meta': {'key': 'iifname'}}, 'right': 'tailscale0'}},
            {'match': {'op': '!=', 'left': {'meta': {'key': 'iifname'}}, 'right': 'lo'}},
            {'match': {'op': '==', 'left': {'payload': {'protocol': protocol, 'field': 'dport'}}, 'right': port}},
            {'drop': None}]}}
    return {'nftables': [
        {'chain': {'family': 'inet', 'table': 'ryadom_media', 'name': 'input', 'type': 'filter', 'hook': 'input', 'prio': -10, 'policy': 'accept'}},
        rule('tcp', 7881), rule('udp', 7882)]}


class LauncherTest(unittest.TestCase):
    def test_setup_reuses_keys_and_refuses_changed_node_without_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'deploy').mkdir()
            (root / 'deploy/setup.sh').write_bytes((PROJECT / 'deploy/setup.sh').read_bytes())
            with patch.object(launcher, 'ROOT', root), contextlib.redirect_stdout(io.StringIO()):
                launcher.prepare_state('ryadom.example.ts.net', '100.80.1.2')
                files = {p.name: p.read_bytes() for p in (root / 'deploy/state').iterdir()}
                launcher.prepare_state('ryadom.example.ts.net', '100.80.1.2')
                self.assertEqual(files, {p.name: p.read_bytes() for p in (root / 'deploy/state').iterdir()})
                with self.assertRaises(launcher.UserError): launcher.prepare_state('new.example.ts.net', '100.80.1.3')
                self.assertEqual(files, {p.name: p.read_bytes() for p in (root / 'deploy/state').iterdir()})

    def test_partial_setup_is_not_deleted_or_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state = root / 'deploy/state'; state.mkdir(parents=True)
            partial = state / 'server.env'; partial.write_text('preserve this')
            with patch.object(launcher, 'ROOT', root), self.assertRaises(launcher.UserError):
                launcher.prepare_state('ryadom.example.ts.net', '100.80.1.2')
            self.assertEqual('preserve this', partial.read_text())

    def test_readiness_requires_ktor_response_and_rejects_wrong_service(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                good = self.path == '/good'
                self.send_response(405 if good else 500 if self.path == '/broken' else 404)
                self.end_headers()
                self.wfile.write(json.dumps({'error': 'Адрес не найден.'} if good else {'other': 'service'}).encode())
            def log_message(self, *args): pass
        server = HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f'http://127.0.0.1:{server.server_port}'
            self.assertTrue(launcher.probe(base + '/good', ktor=True))
            self.assertFalse(launcher.probe(base + '/wrong', ktor=True))
            self.assertFalse(launcher.probe(base + '/broken'))
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=3)
        self.assertFalse(thread.is_alive())

    def test_fresh_docker_install_uses_temporary_group_without_account_changes(self):
        args = launcher.deployment_command(docker_group=True)
        self.assertEqual('sudo', args[0])
        self.assertIn('--group', args)
        self.assertEqual('docker', args[args.index('--group') + 1])
        self.assertIn('RYADOM_MEDIA_FIREWALL_CONFIRMED=yes', args)
        self.assertNotIn('usermod', args)

    def test_operator_setup_requires_confirmation_and_preserves_other_operator(self):
        events = []
        username = launcher.pwd.getpwuid(os.getuid()).pw_name
        def fake_admin(args, **kwargs):
            events.append(tuple(args))
            return subprocess.CompletedProcess(args, 0, json.dumps({'OperatorUser': ''}), '')
        with patch.object(launcher.os, 'geteuid', return_value=1000), patch.object(launcher, 'admin', side_effect=fake_admin), \
             patch.object(launcher, 'confirm', side_effect=lambda message: events.append('confirmed')):
            launcher.ensure_operator()
        self.assertIn(('tailscale', 'set', '--operator=' + username), events)
        self.assertLess(events.index('confirmed'), events.index(('tailscale', 'set', '--operator=' + username)))
        with patch.object(launcher.os, 'geteuid', return_value=1000), \
             patch.object(launcher, 'admin', return_value=subprocess.CompletedProcess([], 0, '{"OperatorUser":"other-person"}', '')) as admin:
            with self.assertRaises(launcher.UserError): launcher.ensure_operator()
            self.assertEqual(1, admin.call_count)

    def test_finds_private_address_without_manual_typing(self):
        status = {'BackendState': 'Running', 'Self': {'DNSName': 'ryadom.example.ts.net.', 'TailscaleIPs': ['fd7a:115c:a1e0::123', '100.80.1.2']}}
        self.assertEqual(('ryadom.example.ts.net', '100.80.1.2'), launcher.node_from_status(status))

    def test_rejects_login_pending_and_invalid_node(self):
        for status in [{'BackendState': 'NeedsLogin'}, {'BackendState': 'Running', 'Self': {'DNSName': 'evil.example', 'TailscaleIPs': ['100.80.1.2']}},
                       {'BackendState': 'Running', 'Self': {'DNSName': 'host.example.ts.net.', 'TailscaleIPs': ['192.168.1.2']}}]:
            with self.subTest(status=status), self.assertRaises(launcher.UserError):
                launcher.node_from_status(status)

    def test_checks_real_firewall_rules_not_a_user_assertion(self):
        good = firewall_document()
        self.assertTrue(launcher.firewall_valid(good))
        for mutate in [lambda doc: doc['nftables'].pop(),
                       lambda doc: doc['nftables'][1]['rule']['expr'].__setitem__(3, {'accept': None}),
                       lambda doc: doc['nftables'][1]['rule']['expr'][0]['match'].__setitem__('op', '=='),
                       lambda doc: doc['nftables'][0]['chain'].__setitem__('hook', 'output'),
                       lambda doc: doc['nftables'].append(copy.deepcopy(doc['nftables'][1]))]:
            changed = copy.deepcopy(good); mutate(changed)
            self.assertFalse(launcher.firewall_valid(changed))

    def test_accepts_explicit_protocol_guard_emitted_by_nft(self):
        document = firewall_document()
        for entry, protocol in zip(document['nftables'][1:], ['tcp', 'udp']):
            entry['rule']['expr'].insert(2, {'match': {'op': '==', 'left': {'meta': {'key': 'l4proto'}}, 'right': protocol}})
        self.assertTrue(launcher.firewall_valid(document))
        document['nftables'][1]['rule']['expr'][2]['match']['right'] = 'udp'
        self.assertFalse(launcher.firewall_valid(document))

    def test_does_not_rebuild_unchanged_server(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            src = root / 'server/src/main/kotlin/Main.kt'; src.parent.mkdir(parents=True); src.write_text('code')
            self.assertTrue(launcher.needs_server_build(root))
            jar = root / 'server/build/install/server/lib/server.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'jar')
            binary = root / 'server/build/install/server/bin/server'; binary.parent.mkdir(parents=True); binary.write_text('binary')
            marker = root / 'server/build/install/server/.ryadom-source.sha256'; marker.write_text(launcher.server_fingerprint(root))
            os.utime(src, (100, 100)); os.utime(jar, (200, 200))
            self.assertFalse(launcher.needs_server_build(root))
            src.write_text('changed code'); os.utime(src, (50, 50))
            self.assertTrue(launcher.needs_server_build(root))

    def test_source_deletion_rebuilds_instead_of_serving_old_bytecode(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            src = root / 'server/src/main/kotlin/Main.kt'; src.parent.mkdir(parents=True); src.write_text('code')
            jar = root / 'server/build/install/server/lib/server.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'jar')
            binary = root / 'server/build/install/server/bin/server'; binary.parent.mkdir(parents=True); binary.write_text('binary')
            marker = root / 'server/build/install/server/.ryadom-source.sha256'; marker.write_text(launcher.server_fingerprint(root))
            os.utime(src, (100, 100)); os.utime(jar, (200, 200))
            self.assertFalse(launcher.needs_server_build(root))
            src.unlink()
            self.assertTrue(launcher.needs_server_build(root))

    def test_help_never_calls_install_or_service_tools(self):
        with tempfile.TemporaryDirectory() as temporary:
            bin_dir = Path(temporary)
            marker = bin_dir / 'called'
            for command in ['sudo', 'docker', 'tailscale', 'nft', 'systemctl', 'curl']:
                executable = bin_dir / command
                executable.write_text('#!/bin/sh\ntouch "' + str(marker) + '"\nexit 89\n'); executable.chmod(0o755)
            env = dict(os.environ, PATH=str(bin_dir) + ':' + os.environ['PATH'])
            result = subprocess.run(['bash', str(PROJECT/'run.sh'), '--help'], env=env, text=True, capture_output=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('./run.sh stop', result.stdout)
            self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
