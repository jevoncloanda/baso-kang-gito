#!/usr/bin/env python3
"""Cloudra release activation. No signing keys, database access or web-server changes."""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import uuid

PAGE = 'https://basokanggito.jevoncl.com'
PACKAGE = 'id.basokanggito.app'
CORE_ASSETS = ('index.html', 'site.css', 'site.js')
ICONS = ('favicon.ico', 'favicon-32x32.png', 'apple-touch-icon.png')
ASSETS = CORE_ASSETS + ICONS
POLICY_KEYS = ('latestVersionCode', 'latestVersionName', 'minimumSupportedVersionCode', 'recommendedVersionCode',
               'allowMissingVersion', 'downloadPageUrl', 'releaseDate', 'downloadUrl', 'sha256', 'releaseNotes', 'mandatoryUpdateMessage')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sync_dir(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_json(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())
    path.chmod(0o644)


def code(value):
    return type(value) is int and 0 < value <= 2147483647


def policy_valid(policy):
    require(all(code(policy.get(key)) for key in ('latestVersionCode', 'minimumSupportedVersionCode', 'recommendedVersionCode')), 'Invalid policy version codes')
    require(policy['minimumSupportedVersionCode'] <= policy['recommendedVersionCode'] <= policy['latestVersionCode'], 'Invalid policy version ordering')
    require(type(policy.get('allowMissingVersion')) is bool and policy.get('downloadPageUrl') == PAGE, 'Invalid compatibility policy')
    require(isinstance(policy.get('mandatoryUpdateMessage'), str) and len(policy['mandatoryUpdateMessage']) <= 1000, 'Invalid update message')
    require(isinstance(policy.get('latestVersionName'), str) and re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}', policy['latestVersionName']), 'Invalid version name')
    notes = policy.get('releaseNotes')
    require(isinstance(notes, list) and len(notes) <= 20 and all(isinstance(note, str) and len(note) <= 500 for note in notes), 'Invalid release notes')
    if policy.get('downloadUrl') is None:
        require(policy.get('sha256') is None and policy.get('releaseDate') is None, 'Incomplete artifact metadata')
    else:
        require(policy['downloadUrl'] == PAGE + '/releases/' + filename(policy), 'Unexpected artifact URL')
        require(re.fullmatch(r'[a-f0-9]{64}', policy.get('sha256', '')) is not None, 'Invalid artifact hash')
        require(re.fullmatch(r'20\d{2}-\d{2}-\d{2}', policy.get('releaseDate', '')) is not None, 'Invalid release date')
        datetime.date.fromisoformat(policy['releaseDate'])
    return {key: policy[key] for key in POLICY_KEYS}


def filename(policy):
    return f"baso-kang-gito-v{policy['latestVersionName']}-{policy['latestVersionCode']}.apk"


def current_target(state):
    target = os.readlink(state / 'current')
    require(re.fullmatch(r'generations/(?:bootstrap|[a-f0-9]{32})', target) is not None, 'Unexpected current link')
    require((state / target).is_dir() and not (state / target).is_symlink(), 'Missing current generation')
    return target


def activate(state, target):
    temporary = state / ('.current-' + uuid.uuid4().hex)
    try:
        temporary.symlink_to(target)
        os.replace(temporary, state / 'current')
        sync_dir(state)
    finally:
        temporary.unlink(missing_ok=True)


def verify_existing_artifact(state, policy):
    if policy['downloadUrl'] is not None:
        artifact = state / 'artifacts' / filename(policy)
        require(artifact.is_file() and not artifact.is_symlink() and digest(artifact) == policy['sha256'], 'Current artifact does not match policy')


def publish(state, stage):
    previous = current_target(state)
    policy = policy_valid(read_json(state / previous / 'policy.json'))
    verify_existing_artifact(state, policy)
    pin = read_json(state / 'production-signing.json')
    manifest = read_json(stage / 'manifest.json')
    require(pin['packageId'] == manifest.get('packageId') == PACKAGE, 'Production package mismatch')
    require(re.fullmatch(r'[a-f0-9]{64}', manifest.get('certificateSha256', '')) is not None and manifest['certificateSha256'] == pin['certificateSha256'], 'Production certificate mismatch')
    version, name = manifest.get('versionCode'), manifest.get('versionName')
    require(code(version) and isinstance(name, str) and re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}', name), 'Invalid candidate version')
    require(version > pin['referenceVersionCode'] and version >= policy['latestVersionCode'], 'Version downgrade or production reference reuse')
    sha = manifest.get('sha256', '')
    require(re.fullmatch(r'[a-f0-9]{64}', sha) is not None and digest(stage / 'artifact.apk') == sha, 'Uploaded APK checksum mismatch')
    expected_files = {'artifact.apk', 'manifest.json', 'publish-cloudra.py', *ASSETS}
    require({entry.name for entry in stage.iterdir()} == expected_files and all(entry.is_file() and not entry.is_symlink() for entry in stage.iterdir()), 'Unexpected staged files or symlinks')
    for asset in ASSETS:
        require(digest(stage / asset) == manifest.get('assets', {}).get(asset), 'Uploaded website checksum mismatch')
    notes = manifest.get('releaseNotes')
    next_policy = policy_valid({**policy, 'latestVersionCode': version, 'latestVersionName': name, 'sha256': sha,
        'downloadUrl': f'{PAGE}/releases/baso-kang-gito-v{name}-{version}.apk',
        'releaseNotes': notes, 'releaseDate': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=7))).date().isoformat()})
    artifact = state / 'artifacts' / filename(next_policy)
    for existing in (state / 'artifacts').glob('*.apk'):
        if existing.name.endswith(f'-{version}.apk') or existing.name.startswith(f'baso-kang-gito-v{name}-'):
            require(existing.name == artifact.name and not existing.is_symlink() and digest(existing) == sha, 'Immutable version already has different identity or bytes')
    if version == policy['latestVersionCode']:
        require(policy['latestVersionName'] == name and policy['sha256'] == sha and policy['downloadUrl'] == next_policy['downloadUrl'], 'Published version cannot be reused')
        return {'changed': False, 'current': previous, 'previous': previous, 'policy': policy}
    numbers = lambda value: tuple([int(part) for part in value.split('.')] + [0] * (4 - len(value.split('.'))))
    require(numbers(name) > numbers(policy['latestVersionName']), 'Version name must increase for a new version code')
    require(not (stage / 'receipt.json').exists(), 'This staging upload has already been activated; use a new upload')
    # A hard link publishes complete, fsynced bytes without ever replacing an immutable file.
    with (stage / 'artifact.apk').open('rb') as source:
        os.fsync(source.fileno())
    (stage / 'artifact.apk').chmod(0o644)
    try:
        os.link(stage / 'artifact.apk', artifact)
    except FileExistsError:
        require(not artifact.is_symlink() and digest(artifact) == sha, 'Immutable artifact checksum mismatch')
    sync_dir(state / 'artifacts')
    return commit(state, previous, next_policy, stage, stage)


def publish_website(state, stage):
    previous = current_target(state)
    policy_path = state / previous / 'policy.json'
    policy = policy_valid(read_json(policy_path))
    verify_existing_artifact(state, policy)
    require({entry.name for entry in stage.iterdir()} - {'receipt.json'} == set(ASSETS) and
            all(entry.is_file() and not entry.is_symlink() for entry in stage.iterdir()),
            'Website upload must contain only the public assets')
    public = state / previous / 'public'
    if all((public / asset).is_file() and (public / asset).read_bytes() == website_asset(stage, asset) for asset in ASSETS):
        return {'changed': False, 'current': previous, 'previous': previous, 'policy': policy,
                'policySha256': digest(policy_path)}
    require(not (stage / 'receipt.json').exists(), 'Use a fresh upload after another generation is activated')
    result = commit(state, previous, policy, stage, stage, policy_path)
    return {**result, 'policySha256': digest(policy_path)}


def website_asset(assets, asset):
    data = (assets / asset).read_bytes()
    if asset == 'index.html':
        # Cloudflare caches CSS, JS and icons. Each changed asset gets a fresh URL
        # while the existing Caddy/Cloudflare cache policy remains untouched.
        for name in ASSETS[1:]:
            url = ('="/' + name + '"').encode()
            if url in data:
                versioned = ('="/' + name + '?v=' + digest(assets / name) + '"').encode()
                data = data.replace(url, versioned)
    return data


def commit(state, previous, policy, assets, stage, policy_source=None):
    generation = 'generations/' + uuid.uuid4().hex
    folder = state / generation
    public = folder / 'public'
    public.mkdir(parents=True, mode=0o755)
    public.chmod(0o755)
    folder.chmod(0o755)
    for asset in ASSETS:
        # Older generations predate website icons; policy-only changes retain that tree.
        if asset in ICONS and not (assets / asset).exists():
            continue
        (public / asset).write_bytes(website_asset(assets, asset))
        (public / asset).chmod(0o644)
        with (public / asset).open('rb') as source:
            os.fsync(source.fileno())
    if policy_source is None:
        write_json(folder / 'policy.json', policy)
    else:
        # Website updates preserve the full publisher-owned document byte for byte.
        shutil.copyfile(policy_source, folder / 'policy.json')
        (folder / 'policy.json').chmod(0o644)
        with (folder / 'policy.json').open('rb') as source:
            os.fsync(source.fileno())
    (public / 'version.json').symlink_to('../policy.json')
    (public / 'releases').symlink_to('../../../artifacts')
    (public / 'latest.apk').symlink_to('releases/' + filename(policy))
    sync_dir(public)
    sync_dir(folder)
    sync_dir(state / 'generations')
    receipt = {'changed': True, 'current': generation, 'previous': previous, 'policy': policy, 'receipt': str(stage / 'receipt.json')}
    write_json(stage / 'receipt.json', receipt)
    sync_dir(stage)
    try:
        activate(state, generation)
    except Exception:
        if current_target(state) == generation:
            activate(state, previous)
        raise
    return receipt


def set_minimum(state, minimum, reject_missing):
    previous = current_target(state)
    policy = policy_valid(read_json(state / previous / 'policy.json'))
    require(reject_missing and code(minimum) and policy['minimumSupportedVersionCode'] <= minimum <= policy['latestVersionCode'], 'Use an explicit supported minimum and --reject-missing only after every device is upgraded and synced')
    require(policy['downloadUrl'] is not None, 'Publish and verify a downloadable release before enforcing updates')
    verify_existing_artifact(state, policy)
    updated = policy_valid({**policy, 'minimumSupportedVersionCode': minimum,
        'recommendedVersionCode': max(minimum, policy['recommendedVersionCode']), 'allowMissingVersion': False})
    stage = state / 'staging' / ('upload-' + uuid.uuid4().hex)
    stage.mkdir(mode=0o755)
    return commit(state, previous, updated, state / previous / 'public', stage)


def rollback(state, stage):
    if not (stage / 'receipt.json').exists():
        return {'restored': False, 'reason': 'No activation receipt; previous release unchanged'}
    receipt = read_json(stage / 'receipt.json')
    current = current_target(state)
    if current == receipt['previous']:
        return {'restored': True, 'current': current}
    require(current == receipt['current'], 'Another release is active; refusing to roll it back')
    previous = receipt['previous']
    require(re.fullmatch(r'generations/(?:bootstrap|[a-f0-9]{32})', previous) is not None, 'Invalid rollback generation')
    verify_existing_artifact(state, policy_valid(read_json(state / previous / 'policy.json')))
    activate(state, previous)
    return {'restored': True, 'current': previous}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('publish', 'publish-website', 'rollback', 'set-minimum'))
    parser.add_argument('--state-root', required=True)
    parser.add_argument('--stage')
    parser.add_argument('--minimum', type=int)
    parser.add_argument('--reject-missing', action='store_true')
    args = parser.parse_args()
    state = Path(args.state_root)
    require(state.is_absolute() and state.resolve() == state and state != Path('/'), 'Use a real absolute release-state directory')
    require(all((state / name).is_dir() and not (state / name).is_symlink() for name in ('staging', 'generations', 'artifacts')), 'Operator must provision release directories first')
    stage = None
    if args.command != 'set-minimum':
        require(args.stage is not None, 'A staging upload is required')
        stage = Path(args.stage)
        require(stage.parent == state / 'staging' and re.fullmatch(r'upload-[a-f0-9]{32}', stage.name) is not None and stage.resolve() == stage, 'Invalid staging path')
        require(os.stat(stage).st_dev == os.stat(state / 'artifacts').st_dev, 'Staging and artifacts must share a filesystem')
    with (state / 'publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.command == 'set-minimum':
            result = set_minimum(state, args.minimum, args.reject_missing)
        elif args.command == 'publish-website':
            result = publish_website(state, stage)
        else:
            result = publish(state, stage) if args.command == 'publish' else rollback(state, stage)
        print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
