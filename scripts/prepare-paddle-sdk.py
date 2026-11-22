"""Fetch pinned Apache-2.0 Paddle SDK/models for the bundled V6 application.

No user screenshots are read by this script. Binaries stay in ignored .tools/.
Run scripts/prepare-ledger-build.py before Gradle.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import io
import json
import shutil
import tarfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--offline', action='store_true', help='Fail if a pinned source/model/notice is not already cached.')
parser.add_argument('--work-dir', type=Path, default=ROOT / '.tools/ocr-ab',
                    help='Override the preparation cache (useful for isolated clean-cache verification).')
args = parser.parse_args()
WORK = args.work_dir.resolve()
LOCK = json.loads((ROOT / 'third_party/ocr-runtime/config/upstream-lock.json').read_text())


def sha(data):
    return hashlib.sha256(data).hexdigest()


def get(url):
    if args.offline:
        raise RuntimeError(f'Pinned input unavailable in offline cache: {url}')
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'Huiming-Ledger-Build'})
            with urllib.request.urlopen(request, timeout=90) as response:
                return response.read()
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2)


def valid(path, digest):
    return path.is_file() and sha(path.read_bytes()) == digest


for item in LOCK['sources']:
    path = WORK / 'sdk' / item['path']
    if valid(path, item['sha256']):
        continue
    upstream = 'LICENSE' if item['path'] == 'LICENSE' else 'deploy/ppocr-android/ppocr-sdk/' + item['path']
    raw = f"https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/{LOCK['commit']}/{upstream}"
    data = get(raw)
    if sha(data) != item['sha256']:
        raise RuntimeError(f'Source checksum mismatch: {upstream}')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)

for model in LOCK['models']:
    folder = WORK / 'models' / model['name']
    if not all(valid(folder / f['path'], f['sha256']) for f in model['extracted']):
        archive = get(model['url'])
        if sha(archive) != model['sha256']:
            raise RuntimeError(f"Archive checksum mismatch: {model['name']}")
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            for file in model['extracted']:
                matches = [m for m in tar.getmembers() if Path(m.name).name == file['path'] and m.isfile()]
                if len(matches) != 1:
                    raise RuntimeError(f"Expected exactly one pinned member: {file['path']}")
                data = tar.extractfile(matches[0]).read()
                if sha(data) != file['sha256']:
                    raise RuntimeError(f"Extracted checksum mismatch: {model['name']}/{file['path']}")
                folder.mkdir(parents=True, exist_ok=True)
                (folder / file['path']).write_bytes(data)
    kind = 'det' if model['name'].endswith('_det') else 'rec'
    target = WORK / 'v6-assets/models/v6' / kind
    target.mkdir(parents=True, exist_ok=True)
    for file in model['extracted']:
        shutil.copyfile(folder / file['path'], target / file['path'])

shutil.copyfile(ROOT / 'third_party/ocr-runtime/config/paddle-sdk.gradle.kts', WORK / 'sdk/build.gradle.kts')
licenses = WORK / 'v6-assets/licenses'
licenses.mkdir(parents=True, exist_ok=True)
shutil.copyfile(WORK / 'sdk/LICENSE', licenses / 'PaddleOCR-Apache-2.0.txt')
(licenses / 'PaddleOCR-source.txt').write_text(
    f"PaddleOCR Android SDK, Copyright (c) 2026 PaddlePaddle Authors.\n"
    f"Apache License 2.0\n{LOCK['repository']}/tree/{LOCK['commit']}\n"
    "Pinned upstream Kotlin originals preserved; separate Gradle build adapter.\n"
    "The build adapter disables CPU arena allocation and retains the modification notice.\n")
for notice in LOCK.get('notices', []):
    path = licenses / notice['file']
    if not valid(path, notice['sha256']):
        # The git blob SHA pins the exact notice independently of release-tag movement.
        repository = notice['url'].split('github.com/')[1].split('/blob/')[0]
        api = f"https://api.github.com/repos/{repository}/git/blobs/{notice['gitBlobSha']}"
        data = base64.b64decode(json.loads(get(api))['content'])
        if sha(data) != notice['sha256']:
            raise RuntimeError(f"Notice checksum mismatch: {notice['file']}")
        path.write_bytes(data)
print('Pinned SDK sources, V6 model pair and notices verified; assets ready.')
