import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import types
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/provision-cloudra.py'
spec = importlib.util.spec_from_file_location('provisioner', SCRIPT)
provisioner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provisioner)


@unittest.skipUnless(os.geteuid() == 0, 'Use a disposable root Linux container for permission proof')
class ProvisioningTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='bkg-provision-')
        self.addCleanup(temporary.cleanup)
        parent = Path(temporary.name)
        parent.chmod(0o755)
        self.stage = parent / 'private-input'
        self.stage.mkdir(mode=0o700)
        self.state = parent / 'state'
        self.public = parent / 'downloads'
        self.backup = parent / 'placeholder-backup'
        self.state.mkdir(mode=0o755)
        self.public.mkdir(mode=0o755)
        os.chown(self.state, 43101, 43101)
        os.chown(self.public, 43102, 43102)
        (self.public / 'index.html').write_text('approved placeholder')
        (self.public / 'index.html').chmod(0o644)
        sources = {'initial-policy.json': SCRIPT.parents[1] / 'release-policy.json',
                   'production-signing.json': SCRIPT.parents[2] / 'android/production-signing.json',
                   'publish-cloudra.py': SCRIPT.with_name('publish-cloudra.py'), 'provision-cloudra.py': SCRIPT}
        for name, source in sources.items():
            shutil.copyfile(source, self.stage / name)
        (self.stage / 'SHA256SUMS').write_text(''.join(hashlib.sha256((self.stage / name).read_bytes()).hexdigest() + '  ' + name + '\n' for name in sources))
        self.addCleanup(mock.patch.stopall)
        mock.patch.multiple(provisioner, STATE=self.state, PUBLIC=self.public, BACKUP=self.backup).start()
        mock.patch.object(provisioner.pwd, 'getpwnam', side_effect=lambda name: types.SimpleNamespace(pw_uid=43101 if name == 'jevon' else 43102, pw_gid=43101 if name == 'jevon' else 43102)).start()
        self.health = mock.patch.object(provisioner, 'health').start()

    def test_provision_permissions_placeholder_and_idempotent_recheck(self):
        provisioner.provision(self.stage)
        self.assertEqual(os.readlink(self.public), str(self.state / 'current/public'))
        self.assertEqual((self.backup / 'index.html').read_text(), 'approved placeholder')
        self.assertEqual((self.public / 'index.html').read_text(), 'approved placeholder')
        self.assertEqual((self.state / 'staging').stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.state / 'publish.lock').stat().st_mode & 0o777, 0o600)
        self.assertTrue(json.loads((self.public / 'version.json').read_text())['allowMissingVersion'])
        provisioner.provision(self.stage)

    def test_corruption_and_partial_state_are_refused(self):
        (self.stage / 'initial-policy.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            provisioner.provision(self.stage)
        self.assertFalse(self.public.is_symlink())
        self.assertFalse(self.backup.exists())

    def test_failed_final_check_restores_original_root(self):
        self.health.side_effect = [None, ValueError('simulated API health failure')]
        with self.assertRaisesRegex(ValueError, 'API health failure'):
            provisioner.provision(self.stage)
        self.assertFalse(self.public.is_symlink())
        self.assertEqual((self.public / 'index.html').read_text(), 'approved placeholder')
        self.assertFalse(self.backup.exists())
        self.health.side_effect = None
        with self.assertRaisesRegex(ValueError, 'not empty'):
            provisioner.provision(self.stage)


if __name__ == '__main__':
    unittest.main()
