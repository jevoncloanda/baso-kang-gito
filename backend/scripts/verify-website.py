#!/usr/bin/env python3
"""Read-only HTTPS integrity gate for website publication; failures trigger caller rollback."""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request
import uuid

PAGE = 'https://basokanggito.jevoncl.com'
API = 'https://basokanggito-api.jevoncl.com'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def get(url, expected_type=None):
    request = urllib.request.Request(url, headers={'Cache-Control': 'no-cache'})
    with urllib.request.urlopen(request, timeout=120) as response:
        require(response.status == 200 and response.url == url, 'Unexpected status or redirect: ' + url)
        if expected_type:
            require(response.headers.get_content_type() in expected_type, 'Unexpected content type: ' + url)
        digest = hashlib.sha256()
        chunks = []
        for chunk in iter(lambda: response.read(1024 * 1024), b''):
            digest.update(chunk)
            if expected_type != ('application/vnd.android.package-archive',):
                chunks.append(chunk)
        return b''.join(chunks), digest.hexdigest(), response.headers


def verify(result, stage):
    nonce = '?verify=' + uuid.uuid4().hex
    raw, sha, headers = get(PAGE + '/version.json' + nonce, ('application/json',))
    require('no-store' in headers.get('Cache-Control', ''), 'version.json must use no-store')
    require(sha == result['policySha256'], 'Published metadata bytes changed')
    policy = json.loads(raw)
    require(all(policy[key] == value for key, value in result['policy'].items()), 'Published policy mismatch')
    server, _, _ = get(API + '/v1/app-version' + nonce, ('application/json',))
    server = json.loads(server)
    require(all(server[key] == value for key, value in result['policy'].items()), 'API compatibility policy mismatch')
    get(API + '/health', ('application/json',))
    assets = {'index.html': ('text/html',), 'site.css': ('text/css',),
              'site.js': ('text/javascript', 'application/javascript'),
              'favicon.ico': ('image/x-icon', 'image/vnd.microsoft.icon'),
              'favicon-32x32.png': ('image/png',), 'apple-touch-icon.png': ('image/png',)}
    for asset, content_types in assets.items():
        _, actual, _ = get(PAGE + '/' + asset + nonce, content_types)
        require(actual == hashlib.sha256((stage / asset).read_bytes()).hexdigest(), 'Public asset mismatch: ' + asset)
    if policy['downloadUrl'] is not None:
        _, actual, headers = get(policy['downloadUrl'], ('application/vnd.android.package-archive',))
        require(actual == policy['sha256'], 'Public APK checksum mismatch')
        require('immutable' in headers.get('Cache-Control', ''), 'APK cache policy changed')
        print('PASS: unchanged APK SHA-256 ' + actual)
    print('PASS: website assets, favicon MIME types, metadata bytes, no-store, API policy and health')


if __name__ == '__main__':
    try:
        verify(json.loads(Path(sys.argv[1]).read_text()), Path(sys.argv[2]))
    except Exception as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
