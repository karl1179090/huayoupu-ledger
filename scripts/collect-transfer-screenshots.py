#!/usr/bin/env python3
"""Copy unmodified synthetic evidence and verify matched browser screenshot dimensions."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import struct

ROOT = Path(__file__).resolve().parents[1]
STATES = ['ledger', 'manual', 'assets', 'statistics', 'bill-file-tools', 'image-import']


def png_size(path):
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
        raise ValueError(f'Not a PNG: {path}')
    return struct.unpack('>II', data[16:24])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, default=ROOT / 'output/transfers-baseline')
    parser.add_argument('--after', type=Path, default=ROOT / 'output/transfers-after')
    parser.add_argument('--native', type=Path, default=ROOT / 'output/transfers-s5-native')
    parser.add_argument('--output', type=Path, default=ROOT / '.github/screenshots/transfers')
    parser.add_argument('--after-context', default='S3 accepted implementation 371f7d2; historical stage screenshots, not final S8 capture')
    args = parser.parse_args()
    before = json.loads((args.before / 'metadata.json').read_text())
    after = json.loads((args.after / 'metadata.json').read_text())
    native = json.loads((args.native / 'parameters.json').read_text())
    for key in ['viewport', 'deviceScaleFactor', 'reducedMotion', 'timezoneId']:
        assert before[key] == after[key], f'Comparison mismatch: {key}'
    parse_time = lambda text: datetime.datetime.fromisoformat(text.replace('Z', '+00:00'))
    assert parse_time(before['now']) == parse_time(after['now']), 'Fixture clock mismatch'
    assert before['errors'] == after['errors'] == [], 'Browser page errors are not accepted'
    args.output.mkdir(parents=True, exist_ok=True)
    images = []

    def copy_image(directory, name, kind):
        path = directory / name
        width, height = png_size(path)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        shutil.copy2(path, args.output / name)
        images.append(dict(file=name, width=width, height=height, sha256=digest, kind=kind))
        return width, height

    pairs = []
    for state in STATES:
        left, right = f'{state}-before.png', f'{state}-after.png'
        a = copy_image(args.before, left, 'synthetic-browser-baseline')
        b = copy_image(args.after, right, 'synthetic-browser-after')
        assert a == b == (390, 844), f'Dimension mismatch: {state}'
        pairs.append(dict(state=state, before=left, after=right))
    for name in ['manual-transfer-after.png', 'transfer-details-after.png', 'statistics-year-precision-after.png']:
        assert copy_image(args.after, name, 'synthetic-browser-extra') == (390, 844)
    for item in native['images']:
        path = args.native / item['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256'], 'Native image hash mismatch'
        assert copy_image(args.native, item['file'], 'synthetic-android-native-preview') == (item['width'], item['height'])
    for name, metadata in [('baseline-metadata.json', before), ('after-metadata.json', after), ('native-metadata.json', native)]:
        (args.output / name).write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    manifest = dict(
        synthetic=True, unchangedImageCopies=True,
        baselineCommit='91aad555f01772e9f4c8f8e70e2da70a30f0879b',
        afterStage=args.after_context,
        nativeStage='S5 r2; actual API30 UiAutomation previews, cancelled after capture; no commit implied',
        pairs=pairs, images=images,
        widget=dict(status='PENDING', reason='Latest MONTH daily calendar and equal dimensions are still being implemented; no old widget images copied'),
    )
    reference = ROOT / 'docs/design/expense-widget-2026-11-06'
    names = ['week.png', 'month.png', 'year.png', 'prompts.json']
    if all((reference / name).is_file() for name in names):
        manifest['designReference'] = dict(kind='User-provided image_gen concept; illustrative amounts, not real ledger or Launcher output',
            files={str((reference / name).relative_to(ROOT)): hashlib.sha256((reference / name).read_bytes()).hexdigest() for name in names})
    (args.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(f'PASS {len(pairs)} same-size/time browser pairs; {len(images)} unchanged synthetic PNGs; widget pending')


if __name__ == '__main__':
    main()
