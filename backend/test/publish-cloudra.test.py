"""Local Linux filesystem/CLI tests. No SSH, signing keys or production access."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import uuid

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
