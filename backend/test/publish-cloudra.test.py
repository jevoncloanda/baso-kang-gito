"""Local Linux filesystem/CLI tests. No SSH, signing keys or production access."""
import importlib.util
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import uuid
from urllib.parse import urlsplit

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'publish-cloudra.py'
spec = importlib.util.spec_from_file_location('publisher', SCRIPT)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublishingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='bkg-publish-')
        self.addCleanup(self.temporary.cleanup)
        self.state = Path(self.temporary.name) / 'state with spaces'
        for name in ('artifacts', 'staging', 'generations/bootstrap/public'):
            (self.state / name).mkdir(parents=True)
        self.pin = {'packageId': publisher.PACKAGE, 'referenceVersionCode': 1, 'certificateSha256': 'a' * 64}
        (self.state / 'production-signing.json').write_text(json.dumps(self.pin))
        self.original = json.loads((SCRIPT.parents[1] / 'release-policy.json').read_text())
        publisher.write_json(self.state / 'generations/bootstrap/policy.json', self.original)
        (self.state / 'generations/bootstrap/public/index.html').write_text('existing placeholder')
        (self.state / 'current').symlink_to('generations/bootstrap')
        self.public_root = Path(self.temporary.name) / 'caddy-root'
        self.public_root.symlink_to(self.state / 'current/public')

    def stage(self, version=2, name='0.0.2', data=b'signed-artifact-fixture'):
        folder = self.state / 'staging' / ('upload-' + uuid.uuid4().hex)
        folder.mkdir()
        (folder / 'artifact.apk').write_bytes(data)
        shutil.copyfile(SCRIPT, folder / 'publish-cloudra.py')
        hashes = {}
        for asset in publisher.ASSETS:
            (folder / asset).write_text('fixture ' + asset)
            hashes[asset] = publisher.digest(folder / asset)
        manifest = {**self.pin, 'versionCode': version, 'versionName': name, 'sha256': publisher.digest(folder / 'artifact.apk'),
                    'releaseNotes': ['Perbaikan Buku Harian'], 'assets': hashes}
        publisher.write_json(folder / 'manifest.json', manifest)
        return folder

    def run_cli(self, command, stage, success=True, identity=None):
        result = subprocess.run([sys.executable, str(SCRIPT), command, '--state-root', str(self.state), '--stage', str(stage)], capture_output=True, text=True, **(identity or {}))
        self.assertEqual(result.returncode, 0 if success else 1, result.stderr)
        return json.loads(result.stdout) if success else result.stderr

    def unchanged(self):
        self.assertEqual(os.readlink(self.state / 'current'), 'generations/bootstrap')
        self.assertEqual((self.public_root / 'index.html').read_text(), 'existing placeholder')

    def website_stage(self):
        folder = self.state / 'staging' / ('upload-' + uuid.uuid4().hex)
        folder.mkdir()
        for asset in publisher.ASSETS:
            (folder / asset).write_text('website ' + asset)
        return folder

    def test_website_preserves_policy_bytes_artifact_pin_and_history(self):
        published = self.run_cli('publish', self.stage())
        old = self.state / published['current']
        # Include publisher-owned fields unknown to this operation and noncanonical formatting.
        raw = json.dumps({**published['policy'], 'futureMetadata': {'keep': True}}, separators=(',', ':')).encode() + b'\n'
        (old / 'policy.json').write_bytes(raw)
        artifact = self.state / 'artifacts' / publisher.filename(published['policy'])
        before = (artifact.stat().st_ino, artifact.read_bytes(), (self.state / 'production-signing.json').read_bytes())
        stage = self.website_stage()
        result = self.run_cli('publish-website', stage)
        self.assertTrue(result['changed'])
        self.assertEqual(result['previous'], published['current'])
        self.assertEqual((self.public_root / 'version.json').read_bytes(), raw)
        self.assertEqual((old / 'policy.json').read_bytes(), raw)
        self.assertEqual((old / 'public/index.html').read_text(), 'fixture index.html')
        self.assertEqual(before, (artifact.stat().st_ino, artifact.read_bytes(), (self.state / 'production-signing.json').read_bytes()))
        self.assertEqual(len(list((self.state / 'artifacts').iterdir())), 1)
        for asset in publisher.ASSETS:
            self.assertEqual((self.public_root / asset).read_bytes(), (stage / asset).read_bytes())
        for retry_stage in (stage, self.website_stage()):
            self.assertFalse(self.run_cli('publish-website', retry_stage)['changed'])
        self.assertTrue(self.run_cli('rollback', stage)['restored'])
        self.assertEqual(os.readlink(self.state / 'current'), published['current'])

    def test_website_bootstrap_and_invalid_stages(self):
        for invalid in ('missing', 'extra', 'symlink'):
            stage = self.website_stage()
            if invalid == 'missing':
                (stage / 'site.js').unlink()
            elif invalid == 'extra':
                (stage / 'artifact.apk').write_bytes(b'not allowed')
            else:
                (stage / 'site.js').unlink()
                (stage / 'site.js').symlink_to(stage / 'site.css')
            self.run_cli('publish-website', stage, success=False)
            self.unchanged()
        raw = (self.state / 'current/policy.json').read_bytes()
        self.run_cli('publish-website', self.website_stage())
        self.assertEqual((self.public_root / 'version.json').read_bytes(), raw)
        self.assertEqual(list((self.state / 'artifacts').iterdir()), [])

    def test_asset_urls_follow_content_hashes_and_retry_is_idempotent(self):
        stage = self.website_stage()
        html = (SCRIPT.parents[2] / 'distribution/index.html').read_bytes()
        (stage / 'index.html').write_bytes(html)
        first = self.run_cli('publish-website', stage)
        rendered = (self.public_root / 'index.html').read_bytes()
        for asset in publisher.ASSETS[1:]:
            self.assertIn(f'="/{asset}?v={publisher.digest(stage / asset)}"'.encode(), rendered)
        self.assertFalse(self.run_cli('publish-website', stage)['changed'])
        updated = self.website_stage()
        (updated / 'index.html').write_bytes(html)
        (updated / 'site.js').write_text('changed JavaScript')
        self.run_cli('publish-website', updated)
        self.assertNotEqual((self.public_root / 'index.html').read_bytes(), rendered)
        self.assertEqual((self.state / first['current'] / 'public/index.html').read_bytes(), rendered)
        self.assertFalse(self.run_cli('publish-website', updated)['changed'])

    def test_public_gate_checks_rendered_html_exact_asset_urls_and_apk(self):
        published = self.run_cli('publish', self.stage())
        stage = self.website_stage()
        (stage / 'index.html').write_bytes((SCRIPT.parents[2] / 'distribution/index.html').read_bytes())
        result = self.run_cli('publish-website', stage)
        gate = runpy.run_path(str(SCRIPT.with_name('verify-website.py')))
        seen = []

        def public_get(url, content_type=None):
            parsed = urlsplit(url)
            seen.append(parsed.path)
            if parsed.path in ('/version.json', '/v1/app-version'):
                path = self.state / 'current/policy.json'
                return path.read_bytes(), publisher.digest(path), {'Cache-Control': 'no-store'}
            if parsed.path == '/health':
                return b'{"status":"ok"}', '', {}
            if parsed.path.startswith('/releases/'):
                return b'', published['policy']['sha256'], {'Cache-Control': 'public, immutable'}
            asset = 'index.html' if parsed.path == '/' else parsed.path[1:]
            expected = publisher.website_asset(stage, asset)
            sha = publisher.hashlib.sha256(expected).hexdigest()
            self.assertEqual(parsed.query, '' if asset == 'index.html' else 'v=' + sha)
            return expected, sha, {}

        with mock.patch.dict(gate['verify'].__globals__, get=public_get):
            gate['verify'](result, stage)
        self.assertIn('/releases/' + publisher.filename(published['policy']), seen)
        with mock.patch.dict(gate['verify'].__globals__, get=lambda *args: (b'wrong', '0' * 64, {'Cache-Control': 'no-store'})):
            with self.assertRaisesRegex(ValueError, 'metadata bytes changed'):
                gate['verify'](result, stage)

        for path in ('/', '/site.css', '/site.js', '/favicon.ico', '/favicon-32x32.png', '/apple-touch-icon.png',
                     '/releases/' + publisher.filename(published['policy'])):
            with self.subTest(corrupt_path=path):
                def corrupted_get(url, content_type=None):
                    body, sha, headers = public_get(url, content_type)
                    if urlsplit(url).path == path:
                        return b'<html>stale page</html>', '0' * 64, headers
                    return body, sha, headers
                with mock.patch.dict(gate['verify'].__globals__, get=corrupted_get):
                    with self.assertRaises(ValueError):
                        gate['verify'](result, stage)

    def test_html_signature_allows_analytics_and_formatting_but_rejects_stale_or_changed_content(self):
        gate = runpy.run_path(str(SCRIPT.with_name('verify-website.py')))
        source = b'<!doctype html><html><body><main id="download" class="release">Baso Kang Gito</main><script type="module" src="/site.js?v=abc"></script>\n</body>\n</html>\n'
        signature = gate['html_signature']
        beacon = (b'<script type="module" src="https://static.cloudflareinsights.com/beacon.min.js/vabc123" '
                  b'integrity="sha512-' + b'A' * 86 + b'==" data-cf-beacon=\'{"version":"2024.11.0","token":"' + b'a' * 32 +
                  b'","r":1,"spa":2}\' crossorigin="anonymous"></script>\n')
        response = source.replace(b'</body>', beacon + b'</body>')
        self.assertEqual(signature(response), signature(source))
        formatted = response.replace(b'id="download" class="release"', b" class='release' id='download'").replace(b'Baso Kang Gito', b'Baso\n Kang Gito')
        self.assertEqual(signature(formatted), signature(source))
        reordered = beacon.replace(b'<script type="module" ', b'<script defer ').replace(b'crossorigin="anonymous"', b'type="module" crossorigin="anonymous"')
        self.assertEqual(signature(source.replace(b'</body>', reordered + b'</body>')), signature(source))
        for unsafe in (response.replace(b'static.cloudflareinsights.com', b'evil.example'),
                       response.replace(b'<script type="module" src="https:', b'<script onclick="evil()" type="module" src="https:'),
                       response.replace(b'></script>', b'>evil()</script>'),
                       response.replace(b'</body>', b'<script>evil()</script></body>'),
                       response.replace(b'Baso Kang Gito', b'Old website'),
                       response.replace(b'/site.js?v=abc', b'/site.js?v=old'),
                       response.replace(beacon, beacon + beacon),
                       response.replace(b'</script>\n</body>', b'</body>'),
                       response.replace(b'crossorigin="anonymous"></script>', b'crossorigin="anonymous"/>')):
            with self.subTest(unsafe=unsafe):
                try:
                    parsed = signature(unsafe)
                except ValueError:
                    continue
                self.assertNotEqual(parsed, signature(source))

    def test_website_corrupt_artifact_and_activation_failure(self):
        self.run_cli('publish', self.stage())
        previous = os.readlink(self.state / 'current')
        stage = self.website_stage()
        with mock.patch.object(publisher, 'activate', side_effect=OSError('interrupted')):
            with self.assertRaises(OSError):
                publisher.publish_website(self.state, stage)
        self.assertEqual(os.readlink(self.state / 'current'), previous)
        (self.public_root / 'latest.apk').write_bytes(b'corrupted fixture')
        self.run_cli('publish-website', self.website_stage(), success=False)
        self.assertEqual(os.readlink(self.state / 'current'), previous)

    def test_website_and_apk_cli_share_lock_and_preserve_latest(self):
        self.run_cli('publish', self.stage())
        website, apk = self.website_stage(), self.stage(3, '0.0.3', b'next')
        # Hold the actual CLI lock so both operations must wait for the same owner.
        with (self.state / 'publish.lock').open('a') as lock:
            publisher.fcntl.flock(lock, publisher.fcntl.LOCK_EX)
            children = [subprocess.Popen([sys.executable, str(SCRIPT), command, '--state-root', str(self.state), '--stage', str(stage)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                        for command, stage in (('publish-website', website), ('publish', apk))]
            for child in children:
                with self.assertRaises(subprocess.TimeoutExpired):
                    child.wait(timeout=0.2)
            publisher.fcntl.flock(lock, publisher.fcntl.LOCK_UN)
        results = [child.communicate(timeout=10) for child in children]
        self.assertEqual([child.returncode for child in children], [0, 0], results)
        policy = publisher.read_json(self.public_root / 'version.json')
        self.assertEqual(policy['latestVersionCode'], 3)
        self.assertEqual(publisher.digest(self.public_root / 'latest.apk'), policy['sha256'])
        receipts = [json.loads(output) for output, _ in results]
        stale = next(stage for stage, result in zip((website, apk), receipts) if result['current'] != os.readlink(self.state / 'current'))
        self.run_cli('rollback', stale, success=False)

    def test_atomic_public_tree_and_single_policy_with_restrictive_umask(self):
        stage = self.stage()
        mask = os.umask(0o077)
        try:
            result = self.run_cli('publish', stage)
        finally:
            os.umask(mask)
        self.assertTrue(result['changed'])
        policy = publisher.read_json(self.public_root / 'version.json')
        self.assertEqual(policy, publisher.read_json(self.state / 'current/policy.json'))
        self.assertEqual(policy['minimumSupportedVersionCode'], 1)
        self.assertTrue(policy['allowMissingVersion'])
        self.assertEqual(publisher.digest(self.public_root / 'latest.apk'), policy['sha256'])
        self.assertEqual(publisher.digest(self.public_root / 'releases' / publisher.filename(policy)), policy['sha256'])
        for asset in publisher.ASSETS:
            self.assertEqual((self.public_root / asset).read_bytes(), (stage / asset).read_bytes())
        for path in (self.state / result['current'], self.public_root.resolve()):
            self.assertEqual(path.stat().st_mode & 0o777, 0o755)
        self.assertEqual((self.public_root / 'version.json').stat().st_mode & 0o777, 0o644)
        self.assertEqual(publisher.read_json(self.state / result['previous'] / 'policy.json'), self.original)

    def test_bad_checksums_and_unexpected_files_never_activate(self):
        for target in ('artifact.apk', 'site.js', 'secret.env'):
            with self.subTest(target=target):
                stage = self.stage()
                (stage / target).write_bytes(b'invalid')
                self.run_cli('publish', stage, success=False)
                self.unchanged()

    def test_idempotency_preservation_downgrade_and_version_reuse(self):
        first = self.run_cli('publish', self.stage())
        artifact = self.public_root / 'latest.apk'
        original_hash = publisher.digest(artifact)
        retry = self.run_cli('publish', self.stage())
        self.assertFalse(retry['changed'])
        self.assertEqual(first['current'], retry['current'])
        self.run_cli('publish', self.stage(data=b'different bytes'), success=False)
        self.run_cli('publish', self.stage(name='0.0.3'), success=False)
        second = self.run_cli('publish', self.stage(3, '0.0.3', b'next release'))
        self.run_cli('publish', self.stage(), success=False)
        self.run_cli('publish', self.stage(4, '0.0.3'), success=False)
        self.run_cli('publish', self.stage(4, '0.0.1'), success=False)
        self.assertEqual(os.readlink(self.state / 'current'), second['current'])
        self.assertEqual(publisher.digest(self.state / 'artifacts/baso-kang-gito-v0.0.2-2.apk'), original_hash)
        self.assertEqual(len(list((self.state / 'artifacts').glob('*.apk'))), 2)

    def test_signature_package_and_paths_rejected(self):
        for field, value in (('packageId', 'id.basokanggito.app.debug'), ('certificateSha256', 'b' * 64), ('versionCode', 1)):
            with self.subTest(field=field):
                stage = self.stage()
                manifest = publisher.read_json(stage / 'manifest.json')
                manifest[field] = value
                (stage / 'manifest.json').write_text(json.dumps(manifest))
                self.run_cli('publish', stage, success=False)
                self.unchanged()
        outside = Path(self.temporary.name) / 'upload-outside'
        outside.mkdir()
        self.run_cli('publish', outside, success=False)
        stage = self.stage()
        linked = self.state / 'staging' / ('upload-' + uuid.uuid4().hex)
        linked.symlink_to(stage)
        self.run_cli('publish', linked, success=False)
        self.unchanged()

    def test_failed_activation_and_public_verification_rollback(self):
        stage = self.stage()
        replace = os.replace

        def interrupted(source, target):
            replace(source, target)
            if os.readlink(target) != 'generations/bootstrap':
                raise OSError('simulated interruption after rename')

        with mock.patch.object(publisher.os, 'replace', side_effect=interrupted):
            with self.assertRaisesRegex(OSError, 'interruption'):
                publisher.publish(self.state, stage)
        self.unchanged()
        stage = self.stage()
        self.run_cli('publish', stage)
        self.assertTrue(self.run_cli('rollback', stage)['restored'])
        self.unchanged()
        self.assertEqual(len(list((self.state / 'artifacts').glob('*.apk'))), 1)

    def test_concurrent_different_bytes_and_stale_rollback(self):
        stages = [self.stage(data=value) for value in (b'first', b'second')]
        children = [subprocess.Popen([sys.executable, str(SCRIPT), 'publish', '--state-root', str(self.state), '--stage', str(stage)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for stage in stages]
        results = [child.communicate() for child in children]
        self.assertEqual(sorted(child.returncode for child in children), [0, 1], results)
        winner = stages[next(index for index, child in enumerate(children) if child.returncode == 0)]
        latest = self.run_cli('publish', self.stage(3, '0.0.3', b'third'))
        self.run_cli('rollback', winner, success=False)
        self.assertEqual(os.readlink(self.state / 'current'), latest['current'])

    def test_minimum_enforcement_is_separate_explicit_and_recoverable(self):
        with self.assertRaises(ValueError):
            publisher.set_minimum(self.state, 1, True)
        published = self.run_cli('publish', self.stage())
        with self.assertRaises(ValueError):
            publisher.set_minimum(self.state, 2, False)
        with self.assertRaises(ValueError):
            publisher.set_minimum(self.state, 3, True)
        result = subprocess.run([sys.executable, str(SCRIPT), 'set-minimum', '--state-root', str(self.state), '--minimum', '2', '--reject-missing'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        receipt = json.loads(result.stdout)
        enforced = publisher.read_json(self.public_root / 'version.json')
        self.assertEqual(enforced['minimumSupportedVersionCode'], 2)
        self.assertFalse(enforced['allowMissingVersion'])
        self.assertEqual(enforced['downloadUrl'], published['policy']['downloadUrl'])
        self.run_cli('rollback', Path(receipt['receipt']).parent)
        self.assertEqual(publisher.read_json(self.public_root / 'version.json'), published['policy'])

    @unittest.skipUnless(os.geteuid() == 0, 'Separate-user permission proof requires a disposable root container')
    def test_deployment_user_can_activate_and_rollback_while_caddy_only_reads(self):
        # Model root-owned /srv, jevon-owned state, and an unrelated caddy identity.
        deploy = {'user': 43101, 'group': 43101, 'extra_groups': [], 'umask': 0o077}
        caddy = {'user': 43102, 'group': 43102, 'extra_groups': []}
        Path(self.temporary.name).chmod(0o755)
        public = self.state / 'generations/bootstrap/public'
        (public / 'version.json').symlink_to('../policy.json')
        (public / 'releases').symlink_to('../../../artifacts')
        (self.state / 'publish.lock').touch()
        stage = self.stage()
        for path in [self.state, *self.state.rglob('*')]:
            os.chown(path, deploy['user'], deploy['group'], follow_symlinks=False)
            if not path.is_symlink():
                path.chmod(0o755 if path.is_dir() else 0o644)
        (self.state / 'staging').chmod(0o700)
        (self.state / 'publish.lock').chmod(0o600)
        check = 'import os,sys; from pathlib import Path; s=Path(sys.argv[1]); assert not os.access(s.parent,os.W_OK); assert os.access(s,os.W_OK)'
        result = subprocess.run([sys.executable, '-c', check, str(self.state)], capture_output=True, text=True, **deploy)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.run_cli('publish', stage, identity=deploy)
        check = ('import os,sys; from pathlib import Path; p=Path(sys.argv[1]); s=Path(sys.argv[2]); '
                 'assert not os.access(s,os.W_OK); assert not os.access(s/"staging",os.R_OK); '
                 '[f.read_bytes() for f in p.iterdir() if f.is_file()]; '
                 '[f.read_bytes() for f in (p/"releases").iterdir()]; '
                 'assert not any(os.access(f,os.W_OK) for f in p.iterdir() if f.is_file())')
        result = subprocess.run([sys.executable, '-c', check, str(self.public_root), str(self.state)], capture_output=True, text=True, **caddy)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.run_cli('rollback', stage, identity=deploy)
        self.unchanged()
        result = subprocess.run([sys.executable, '-c', check, str(self.public_root), str(self.state)], capture_output=True, text=True, **caddy)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
