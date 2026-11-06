"""Verify pinned V6 inputs and generate the complete app's offline models/notices.

Run by PrepareLedgerAssets after web protection. Only its generated output is changed.
No network access, user screenshots or source assets are used as output targets.
"""
import argparse
import os
import re
import hashlib
import html
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def generated_output(value):
    explicit_root = os.environ.get('LEDGER_GENERATED_BUILD_ROOT')
    if explicit_root and not Path(explicit_root).is_absolute():
        raise RuntimeError('Generated build root must be absolute')
    build_root = Path(explicit_root or ROOT / 'app/build').resolve()
    output = Path(value).resolve()
    source_assets = (ROOT / 'app/src/main/assets').resolve()
    if build_root == source_assets or build_root in source_assets.parents or source_assets in build_root.parents:
        raise RuntimeError('Generated build root must remain separate from source assets')
    try:
        parts = output.relative_to(build_root / 'generated').parts
    except ValueError as error:
        raise RuntimeError('Only a generated ledger assets directory may be modified') from error
    if len(parts) != 2 or not (
        parts[0] == 'ledgerAssets' and re.fullmatch(r'[A-Za-z0-9_-]+', parts[1]) or
        parts[0] == 'assets' and re.fullmatch(r'prepare[A-Za-z0-9]+LedgerAssets', parts[1])
    ):
        raise RuntimeError('Only a generated ledger assets directory may be modified')
    if output == source_assets or output in source_assets.parents or source_assets in output.parents:
        raise RuntimeError('Generated output must remain separate from source assets')
    return output


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(path, expected):
    if not path.is_file() or digest(path) != expected:
        raise RuntimeError(f'Pinned V6 input missing/changed: {path.relative_to(ROOT)}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--abi', required=True, choices=['arm64-v8a', 'x86_64'])
    parser.add_argument('--coordinates', required=True, type=Path)
    args = parser.parse_args()
    output = generated_output(args.output)
    lock = json.loads((ROOT / 'third_party/ocr-runtime/config/upstream-lock.json').read_text(encoding='utf-8'))
    native = json.loads((ROOT / 'third_party/ocr-runtime/config/ledger-v6-runtime-lock.json').read_text(encoding='utf-8'))
    if digest(ROOT / 'third_party/ocr-runtime/config/paddle-sdk.gradle.kts') != digest(ROOT / '.tools/ocr-ab/sdk/build.gradle.kts'):
        raise RuntimeError('SDK build adapter differs from the tracked adapter; rerun prepare-ledger-build.py')
    for item in lock['sources']:
        verify(ROOT / '.tools/ocr-ab/sdk' / item['path'], item['sha256'])
    for item in native['files']:
        if item['path'].endswith(f'-{args.abi}.aar'):
            verify(ROOT / item['path'], item['sha256'])
    model_manifest = []
    for model in lock['models']:
        if not model['name'].startswith('PP-OCRv6_'):
            continue
        kind = 'det' if model['name'].endswith('_det') else 'rec'
        for item in model['extracted']:
            relative = f'models/v6/{kind}/{item["path"]}'
            source = ROOT / '.tools/ocr-ab/v6-assets' / relative
            verify(source, item['sha256'])
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            model_manifest.append(dict(path=relative, sha256=item['sha256']))
    licenses = output / 'licenses'
    inventory = json.loads((licenses / 'inventory.json').read_text(encoding='utf-8'))
    coordinates = set(args.coordinates.read_text(encoding='utf-8').splitlines())
    if any(c.startswith(('com.microsoft.onnxruntime:onnxruntime-android:', 'org.opencv:opencv:')) for c in coordinates):
        raise RuntimeError('V6 ledger must use pinned reduced runtime AARs, not full benchmark libraries')
    inventory = list({entry['coordinate']: entry for entry in inventory if entry['coordinate'] in coordinates}.values())
    required_notices = {notice['file'] for entry in inventory for notice in entry['original_notices']}
    # Keep generated copies of all original notices required by resolved runtime coordinates.
    for path in licenses.iterdir():
        if path.is_file() and path.name not in required_notices | {'index.html', 'inventory.json'}:
            path.unlink()
    def add_notice(coordinate, name, source, expected=None):
        if expected:
            verify(source, expected)
        target = licenses / name
        shutil.copyfile(source, target)
        entry = next((entry for entry in inventory if entry['coordinate'] == coordinate), None)
        if entry is None:
            entry = dict(coordinate=coordinate, original_notices=[])
            inventory.append(entry)
        entry['original_notices'].append(dict(file=name, sha256=digest(target)))
    add_notice('Huiming:Ledger:GPL-3.0-only', 'project-GPL-3.0.txt', ROOT / 'LICENSE')
    add_notice('PaddlePaddle:PaddleOCR:V6-Tiny', 'PaddleOCR-Apache-2.0.txt', ROOT / '.tools/ocr-ab/sdk/LICENSE')
    for notice in lock['notices']:
        coordinate = 'org.opencv:opencv:4.12.0-minimal' if notice['file'].startswith('OpenCV') else 'com.microsoft.onnxruntime:onnxruntime-android:1.21.1-reduced'
        add_notice(coordinate, notice['file'], ROOT / '.tools/ocr-ab/v6-assets/licenses' / notice['file'], notice['sha256'])
    extra = ROOT / 'third_party/ocr-runtime/licenses'
    for notice in json.loads((extra / 'additional-notices.json').read_text(encoding='utf-8')):
        add_notice(notice['component'], notice['file'], extra / notice['file'], notice['sha256'])
    provenance = dict(engine='paddle-v6-tiny', sourceCommit=lock['commit'], sourceRepository=lock['repository'],
                      buildAdapterSha256=digest(ROOT / 'third_party/ocr-runtime/config/paddle-sdk.gradle.kts'), cpuArena=False, numThreads=4,
                      models=model_manifest, abi=args.abi, nativeRuntimes=[entry for entry in native['files'] if entry['path'].endswith(f'-{args.abi}.aar')],
                      modifications='Separate Gradle adapter disables CPU arena allocation; model weights and pinned upstream source files are unchanged.')
    (output / 'v6-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
    (licenses / 'inventory.json').write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    entries = []
    for entry in inventory:
        links = ''.join(f'<li><a href="{html.escape(n["file"])}">{html.escape(n["file"])}</a></li>' for n in entry['original_notices'])
        terms = ' / '.join(html.escape(license.get('name', '') + ' ' + license.get('url', '')) for license in entry.get('licenses', []))
        entries.append(f'<li><strong>{html.escape(entry["coordinate"])}</strong><p>{terms}</p><ul>{links}</ul></li>')
    (licenses / 'index.html').write_text(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">'
        '<title>第三方依赖与许可</title><style>body{font:16px sans-serif;line-height:1.65;padding:16px;overflow-wrap:anywhere}a{color:#087b62}</style>'
        '<h1>第三方依赖与许可 · V6</h1><p>本版本使用 PaddleOCR V6 Tiny、精简 ONNX Runtime 和 OpenCV。'
        '模型与基础库适用各自条款；已保留原始许可通知。CPU 内存策略的修改声明另随 SDK 打包。</p><ol>'
        + ''.join(entries) + '</ol><p>Font Awesome 完整通知另见“字体与图标声明”。</p></html>', encoding='utf-8')
    print(f'Verified V6: {len(model_manifest)} model/config assets, {len(lock["sources"])} source files; {len(inventory)} notice entries.')


if __name__ == '__main__':
    main()
