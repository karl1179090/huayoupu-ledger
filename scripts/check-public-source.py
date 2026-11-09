"""Check public sources and optionally export a ZIP without local data or Git history.

Checks current files, pinned native inputs and a small set of high-confidence secret
formats. This is not an exhaustive credential or binary-content audit. Works from
Git working trees and from an extracted source ZIP. Never modifies the Git index.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIRECTORIES = {'app', 'core', 'gradle', 'scripts', 'tools', 'third_party', '.github'}
PUBLIC_DOC_DIRECTORIES = {'docs/agents', 'docs/adr', 'docs/specs', 'docs/tickets', 'docs/verification'}
PUBLIC_DOC_FILES = {'docs/recognition-node-fast-path.md'}
ROOT_FILES = {'README.md', 'NOTICE.md', 'LICENSE', 'CONTRIBUTING.md', 'SECURITY.md', 'AGENTS.md', 'GLOSSARY.md',
              '.gitignore', '.gitattributes', 'build.gradle.kts', 'settings.gradle.kts',
              'gradle.properties', 'gradlew', 'gradlew.bat'}
PRIVATE_PARTS = {'build', 'node_modules', '__pycache__', '.gradle', '.kotlin', '.tools', '.git'}
PRIVATE_SUFFIXES = {'.log', '.pyc', '.apk', '.aab', '.jks', '.keystore', '.db', '.sqlite',
                    '.sqlite3', '.hprof', '.pem', '.p12', '.pfx'}
SECRET_PATTERNS = {
    'github-token': rb'gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,}',
    'openai-token': rb'sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}',
    'google-api-key': rb'AIza[A-Za-z0-9_-]{35}',
    'aws-access-key': rb'AKIA[A-Z0-9]{16}',
    'private-key': rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----',
}


def publishable(name):
    if '\\' in name:
        return False
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or not path.parts:
        return False
    public_doc = name in PUBLIC_DOC_FILES or any(path.is_relative_to(directory) for directory in PUBLIC_DOC_DIRECTORIES)
    if path.parts[0] not in DIRECTORIES and name not in ROOT_FILES and not public_doc:
        return False
    if PRIVATE_PARTS.intersection(path.parts) or path.suffix.lower() in PRIVATE_SUFFIXES:
        return False
    if path.name == 'local.properties' or path.name.startswith('.env') or path.name in {'id_rsa', 'id_ed25519'}:
        return False
    return True


def secret_kinds(data):
    return [kind for kind, pattern in SECRET_PATTERNS.items() if re.search(pattern, data)]


def public_files():
    if (ROOT / '.git').exists():
        raw = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT)
        names = raw.decode('utf-8').split('\0')
    else:
        names = [p.relative_to(ROOT).as_posix() for directory in DIRECTORIES | PUBLIC_DOC_DIRECTORIES if (ROOT / directory).is_dir()
                 for p in (ROOT / directory).rglob('*') if p.is_file()]
        names += [name for name in ROOT_FILES | PUBLIC_DOC_FILES if (ROOT / name).is_file()]
    files = []
    for name in sorted(set(names)):
        if not publishable(name):
            continue
        path = ROOT / name
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            raise RuntimeError(f'External/symlink source cannot be exported: {name}')
        if path.is_file():
            files.append((name, path))
    return files


def verify_lock(path, expected, size=None):
    if not path.is_file() or (size is not None and path.stat().st_size != size):
        raise RuntimeError(f'Missing/invalid pinned input: {path.relative_to(ROOT)}')
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f'Pinned checksum mismatch: {path.relative_to(ROOT)}')


def check(files):
    names = {name for name, _ in files}
    required = ROOT_FILES | {'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
                            'app/src/main/AndroidManifest.xml', 'scripts/prepare-ledger-build.py',
                            'scripts/ledger-release.py', 'gradle/release-version.json',
                            'scripts/prepare-ledger-v6-assets.py', 'scripts/prepare-paddle-sdk.py',
                            'third_party/ocr-runtime/config/upstream-lock.json',
                            'third_party/ocr-runtime/config/ledger-v6-runtime-lock.json',
                            'third_party/ocr-runtime/config/paddle-sdk.gradle.kts',
                            'third_party/ocr-runtime/README.md'}
    missing = sorted(required - names)
    if missing:
        raise RuntimeError(f'Missing public build/documentation inputs: {missing}')
    if hashlib.sha256((ROOT / 'LICENSE').read_bytes()).hexdigest() != 'c7a7e34f4d1989d6ece859e33c2b484d6f09805f15997f8c799c6619437ec0d4':
        raise RuntimeError('Root LICENSE must contain the unmodified GPL-3.0 text')
    lock = json.loads((ROOT / 'third_party/ocr-runtime/config/ledger-v6-runtime-lock.json').read_text(encoding='utf-8'))
    for item in lock['files']:
        verify_lock(ROOT / 'third_party/ocr-runtime' / Path(item['path']).name, item['sha256'], item['bytes'])
    upstream = json.loads((ROOT / 'third_party/ocr-runtime/config/upstream-lock.json').read_text(encoding='utf-8'))
    notices = ROOT / 'third_party/ocr-runtime/licenses'
    for item in upstream['notices'] + json.loads((notices / 'additional-notices.json').read_text(encoding='utf-8')):
        verify_lock(notices / item['file'], item['sha256'])
    eigen = lock['files'][0]['build']['eigenSourceSha256']
    verify_lock(ROOT / 'third_party/ocr-runtime/sources/eigen-1d8b82b.tar.gz', eigen)
    findings = []
    manifest = []
    for name, path in files:
        data = path.read_bytes()
        if len(data) >= 50 * 1024 * 1024:
            raise RuntimeError(f'Public file exceeds the 50 MiB review limit: {name}')
        if b'\0' not in data:
            # Report kind/path only; never echo a matched credential.
            findings.extend({'path': name, 'kind': kind} for kind in secret_kinds(data))
        manifest.append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    if findings:
        raise RuntimeError('Potential credentials in public files: ' + json.dumps(findings, ensure_ascii=True))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='Write checked current sources as a ZIP, without Git history.')
    args = parser.parse_args()
    files = public_files()
    manifest = check(files)
    if args.output:
        output = args.output.resolve()
        if output.suffix != '.zip' or output.relative_to(ROOT).parts[0] != 'output':
            raise RuntimeError('Source ZIP must be written below this project output/ directory')
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, path in files:
                archive.write(path, name)
            archive.writestr('SOURCE-MANIFEST.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        output.with_suffix('.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest() + '  ' + output.name + '\n', encoding='utf-8')
    print(f'PASS: {len(files)} public files; pinned native inputs/notices verified; no configured secret patterns found.')
    if args.output:
        print(f'Source ZIP: {args.output}')


if __name__ == '__main__':
    main()
