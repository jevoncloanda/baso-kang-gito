#!/usr/bin/env python3
"""Reviewed one-time /srv setup; run manually with sudo, never by the publisher."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import runpy
import shutil
import stat
import subprocess
import sys
import urllib.request
import uuid

STATE = Path('/srv/baso-kang-gito-release-state')
PUBLIC = Path('/srv/baso-kang-gito-downloads')
BACKUP = Path('/srv/baso-kang-gito-downloads.placeholder-backup')
FILES = ('initial-policy.json', 'production-signing.json', 'publish-cloudra.py', 'provision-cloudra.py')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def health():
    with urllib.request.urlopen('http://127.0.0.1:3000/health', timeout=10) as response:
        require(response.status == 200 and json.load(response).get('status') == 'ok', 'Existing API is not healthy')


def as_user(account, program, *args):
    result = subprocess.run([sys.executable, '-c', program, *map(str, args)], user=account.pw_uid,
        group=account.pw_gid, extra_groups=[], cwd='/', capture_output=True, text=True)
    require(result.returncode == 0, 'Permission verification failed: ' + result.stderr.strip())


def verify(deploy, caddy):
    require(os.readlink(PUBLIC) == str(STATE / 'current/public'), 'Unexpected public symlink')
    require(os.readlink(STATE / 'current') == 'generations/bootstrap', 'Unexpected bootstrap link')
    as_user(caddy, 'from pathlib import Path; import os,sys; s=Path(sys.argv[1]); p=Path(sys.argv[2]); '
        'assert not os.access(s,os.W_OK); assert not os.access(s/"staging",os.R_OK); '
        '[f.open("rb").close() for f in p.rglob("*") if f.is_file()]; '
        'assert (p/"index.html").is_file(); assert (p/"version.json").is_file()', STATE, PUBLIC)
    as_user(deploy, 'from pathlib import Path; import os,sys,uuid; s=Path(sys.argv[1]); '
        'assert all(os.access(s/p,os.W_OK) for p in (".","artifacts","generations","staging")); '
        'a=s/(".probe-"+uuid.uuid4().hex); b=s/(".probe-"+uuid.uuid4().hex); '
        'a.symlink_to("generations/bootstrap"); os.replace(a,b); b.unlink()', STATE)
    health()


def provision(stage):
    require(os.geteuid() == 0, 'Run this reviewed script manually with sudo')
    require(stage.is_absolute() and stage.resolve() == stage, 'Use the private real staging directory')
    deploy, caddy = pwd.getpwnam('jevon'), pwd.getpwnam('caddy')
    expected = {}
    for line in (stage / 'SHA256SUMS').read_text().splitlines():
        checksum, name = line.split('  ', 1)
        require(name in FILES and name not in expected, 'Unexpected checksum manifest entry')
        expected[name] = checksum
    require(set(expected) == set(FILES), 'Incomplete provisioning bundle')
    for name in FILES:
        source = stage / name
        require(source.is_file() and not source.is_symlink(), 'Inputs must be regular files')
        require(hashlib.sha256(source.read_bytes()).hexdigest() == expected[name], 'Provisioning input checksum mismatch')
    publisher = runpy.run_path(str(stage / 'publish-cloudra.py'))
    policy = publisher['policy_valid'](publisher['read_json'](stage / 'initial-policy.json'))
    require(policy['minimumSupportedVersionCode'] == 1 and policy['allowMissingVersion'] is True and policy['downloadUrl'] is None, 'Expected unpublished transitional policy; do not reset an active release')
    pin = publisher['read_json'](stage / 'production-signing.json')
    require(pin == {'packageId': 'id.basokanggito.app', 'referenceVersionCode': 1,
        'certificateSha256': '25bffbd781d0891b687658628e94d3bb71bdeb8883d1b91639725d3a1b006e99'}, 'Production certificate pin mismatch')
    require(STATE.parent == PUBLIC.parent == BACKUP.parent and STATE.parent.resolve() == STATE.parent, 'Unexpected /srv layout')
    require(STATE.is_dir() and not STATE.is_symlink() and STATE.stat().st_uid == deploy.pw_uid, 'Provision jevon-owned real release-state directory first')
    health()
    if PUBLIC.is_symlink():
        verify(deploy, caddy)
        require(publisher['read_json'](STATE / 'current/policy.json') == policy, 'Existing policy differs; inspection required')
        print('PASS: existing bootstrap verified; no files replaced')
        return
    require(PUBLIC.is_dir() and not BACKUP.exists() and not BACKUP.is_symlink(), 'Preserved placeholder backup already exists or public root is missing')
    require(not list(STATE.iterdir()), 'Release state is not empty; inspect partial initialization before retrying')
    require(all(not entry.is_symlink() and (entry.is_file() or entry.is_dir()) for entry in PUBLIC.rglob('*')), 'Inspect special files/symlinks in placeholder before provisioning')
    require((PUBLIC / 'index.html').is_file(), 'Placeholder index.html is missing')
    original = hashlib.sha256((PUBLIC / 'index.html').read_bytes()).hexdigest()
    boot = STATE / 'generations/bootstrap'
    for folder in (STATE, STATE / 'artifacts', STATE / 'generations', boot):
        folder.mkdir(exist_ok=True)
        folder.chmod(0o755)
        os.chown(folder, deploy.pw_uid, deploy.pw_gid)
    staging = STATE / 'staging'
    staging.mkdir(mode=0o700)
    os.chown(staging, deploy.pw_uid, deploy.pw_gid)
    shutil.copytree(PUBLIC, boot / 'public')
    require(not any((boot / 'public' / name).exists() for name in ('version.json', 'releases', 'latest.apk')), 'Placeholder has release content; inspect it before proceeding')
    publisher['write_json'](boot / 'policy.json', policy)
    shutil.copyfile(stage / 'production-signing.json', STATE / 'production-signing.json')
    (STATE / 'production-signing.json').chmod(0o644)
    (STATE / 'publish.lock').touch(mode=0o600)
    (boot / 'public/version.json').symlink_to('../policy.json')
    (boot / 'public/releases').symlink_to('../../../artifacts')
    (STATE / 'current').symlink_to('generations/bootstrap')
    for entry in STATE.rglob('*'):
        os.chown(entry, deploy.pw_uid, deploy.pw_gid, follow_symlinks=False)
        if not entry.is_symlink() and entry != staging:
            entry.chmod(0o755 if entry.is_dir() else (0o600 if entry.name == 'publish.lock' else 0o644))
    temporary = PUBLIC.parent / ('.bkg-public-' + uuid.uuid4().hex)
    temporary.symlink_to(STATE / 'current/public')
    try:
        PUBLIC.rename(BACKUP)
        try:
            temporary.rename(PUBLIC)
            verify(deploy, caddy)
            require(hashlib.sha256((PUBLIC / 'index.html').read_bytes()).hexdigest() == original, 'Placeholder content changed')
        except Exception:
            if PUBLIC.is_symlink() and os.readlink(PUBLIC) == str(STATE / 'current/public'):
                PUBLIC.unlink()
            if not PUBLIC.exists():
                BACKUP.rename(PUBLIC)
            raise
    finally:
        temporary.unlink(missing_ok=True)
    print('PASS: bootstrap provisioned; placeholder preserved; API healthy; jevon can activate links; caddy can read without write access')
    print('Public root: ' + str(PUBLIC) + ' -> ' + os.readlink(PUBLIC))
    print('Placeholder backup: ' + str(BACKUP))


if __name__ == '__main__':
    try:
        provision(Path(__file__).resolve().parent)
    except Exception as error:
        print('FAIL: ' + str(error) + '. No API/database/Caddy configuration was changed. Inspect retained state before retrying.', file=sys.stderr)
        sys.exit(1)
