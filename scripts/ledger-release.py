#!/usr/bin/env python3
"""Version and publish the signed artifacts of one verified main build."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'karl1179090/huayoupu-ledger'
REQUIRED_JOBS = {'source', 'browser', 'verify', 'ARM64 APK'}
SHA = re.compile(r'[0-9a-f]{40}')
VERSION = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version_for_commit(root, config, commit, development=False):
    code = config['baselineVersionCode']
    patch = config['baselinePatch']
    require(type(code) is int and type(patch) is int and code > 0 and patch >= 0,
            'Invalid release version baseline')
    distance = 0
    if not development:
        require(SHA.fullmatch(commit), 'Release commit must be a full SHA')
        baseline = config['baselineCommit']
        require(SHA.fullmatch(baseline), 'Baseline must be a full SHA')
        lineage = subprocess.check_output(
            ['git', 'rev-list', '--first-parent', commit], cwd=root, text=True).splitlines()
        require(baseline in lineage, 'Release baseline is missing from first-parent history; fetch full history')
        distance = lineage.index(baseline)
    code += distance
    name = f"{config['major']}.{config['minor']}.{patch + distance}{config['suffix']}"
    require(0 < code <= 2_100_000_000 and VERSION.fullmatch(name), 'Invalid Android version')
    return {'versionCode': code, 'versionName': name, 'tag': 'v' + name,
            'firstParentDistance': distance}


def run_checks_ready(run, jobs, commit):
    require(SHA.fullmatch(commit), 'Invalid build commit')
    require(run['repository']['full_name'] == REPOSITORY and
            run['head_repository']['full_name'] == REPOSITORY, 'Build must belong to the trusted repository')
    require(run['path'] == '.github/workflows/android.yml' and run['event'] == 'push' and
            run['head_branch'] == 'main' and run['head_sha'] == commit,
            'Build must be the exact main push, from android.yml')
    latest = {}
    for job in jobs:
        if job['name'] in REQUIRED_JOBS:
            previous = latest.get(job['name'])
            order = (job.get('run_attempt', 1), job['id'])
            if previous is None or order > (previous.get('run_attempt', 1), previous['id']):
                latest[job['name']] = job
    for job in latest.values():
        if job['status'] == 'completed':
            require(job['conclusion'] == 'success', 'Required check failed or was skipped: ' + job['name'])
    ready = set(latest) == REQUIRED_JOBS and all(
        job['status'] == 'completed' and job['conclusion'] == 'success' for job in latest.values())
    require(ready or run['status'] != 'completed', 'Completed build is missing successful required checks')
    return ready


def validate_metadata(metadata, commit, tag):
    require(SHA.fullmatch(commit), 'Invalid release commit')
    require(metadata['commit'] == commit and tag == 'v' + metadata['version'] and
            VERSION.fullmatch(metadata['version']), 'Version or source commit does not match')
    require(type(metadata['versionCode']) is int and 0 < metadata['versionCode'] <= 2_100_000_000,
            'Invalid versionCode')
    require(metadata['package'] == 'net.ankio.ledger' and metadata['abi'] == 'arm64-v8a' and
            metadata['signed'] is True, 'Only signed ARM64 production artifacts can be published')
    filename = metadata['filename']
    require(Path(filename).name == filename and filename.endswith('-arm64-v8a.apk') and
            not filename.endswith('-unsigned.apk'), 'Invalid production APK filename')
    require(re.fullmatch(r'[0-9a-f]{64}', metadata['sha256']), 'Invalid APK checksum')


def make_latest(metadata, previous):
    if previous is None:
        return True
    require(metadata['versionCode'] != previous['versionCode'] or
            metadata['commit'] == previous['commit'], 'Different commits cannot share the same versionCode')
    return metadata['versionCode'] > previous['versionCode']


def gh(*arguments, missing_ok=False):
    result = subprocess.run(['gh', *map(str, arguments)], capture_output=True, text=True)
    if result.returncode:
        if missing_ok:
            try:
                if str(json.loads(result.stdout).get('status')) == '404':
                    return None
            except (ValueError, AttributeError):
                pass
        raise RuntimeError('GitHub command failed: ' + ' '.join(map(str, arguments)) + '\n' + result.stderr)
    return result.stdout


def api(path, missing_ok=False):
    raw = gh('api', f'repos/{REPOSITORY}/{path}', missing_ok=missing_ok)
    return json.loads(raw) if raw is not None else None


def find_release(tag):
    # The tag endpoint only finds published releases. The authenticated list
    # also finds drafts whose tag is still pending.
    pages = json.loads(gh('api', f'repos/{REPOSITORY}/releases?per_page=100',
                         '--paginate', '--slurp'))
    matches = [release for page in pages for release in page if release['tag_name'] == tag]
    require(len(matches) <= 1, 'Multiple releases share this tag')
    return matches[0] if matches else None


def remote_tag_commit(tag):
    ref = api('git/ref/tags/' + quote(tag, safe=''), missing_ok=True)
    if ref is None:
        return None
    obj = ref['object']
    for _ in range(8):
        if obj['type'] == 'commit':
            return obj['sha']
        require(obj['type'] == 'tag', 'Tag does not point to a commit')
        obj = api('git/tags/' + obj['sha'])['object']
    raise ValueError('Excessively nested annotated tag')


def release_metadata(tag, directory):
    gh('release', 'download', tag, '--repo', REPOSITORY, '--pattern', 'build-info.json',
       '--dir', directory)
    metadata = json.loads((Path(directory) / 'build-info.json').read_text())
    validate_metadata(metadata, metadata['commit'], tag)
    return metadata


def wait_for_checks(run_id, commit):
    require(str(run_id).isdigit(), 'Invalid build run ID')
    deadline = time.monotonic() + 35 * 60
    while True:
        run = api('actions/runs/' + str(run_id))
        pages = json.loads(gh('api', f'repos/{REPOSITORY}/actions/runs/{run_id}/jobs?filter=all&per_page=100',
                             '--paginate', '--slurp'))
        jobs = [job for page in pages for job in page['jobs']]
        if run_checks_ready(run, jobs, commit):
            print(f'Verified four successful main checks: run {run_id}, commit {commit}')
            return
        require(time.monotonic() < deadline, 'Timed out waiting for automatic Android checks')
        # Do not wait for the entire run: this publishing job belongs to that run.
        time.sleep(10)


def publish(directory, commit, tag, notes):
    directory = directory.resolve()
    metadata = json.loads((directory / 'build-info.json').read_text())
    validate_metadata(metadata, commit, tag)
    files = [directory / name for name in [metadata['filename'], metadata['filename'] + '.sha256',
             'Huayoupu-Ledger-source.zip', 'Huayoupu-Ledger-source.sha256', 'build-info.json']]
    require(all(path.is_file() for path in files), 'Incomplete release artifacts')
    require(hashlib.sha256(files[0].read_bytes()).hexdigest() == metadata['sha256'], 'APK checksum mismatch')
    for payload, checksum in [(files[0], files[1]), (files[2], files[3])]:
        fields = checksum.read_text().strip().split()
        require(len(fields) == 2 and fields[1] == payload.name and
                fields[0] == hashlib.sha256(payload.read_bytes()).hexdigest(), 'Artifact checksum mismatch')
    existing_commit = remote_tag_commit(tag)
    require(existing_commit is None or existing_commit == commit, 'Existing tag points to a different commit')
    existing = find_release(tag)
    if existing is not None and not existing['draft']:
        with tempfile.TemporaryDirectory() as temp:
            previous = release_metadata(tag, temp)
        require(previous['commit'] == commit and previous['versionCode'] == metadata['versionCode'],
                'Published release has a different source or version')
        require({path.name for path in files}.issubset({a['name'] for a in existing['assets']}),
                'Published release is missing required assets')
        print('Already published; keeping immutable assets: ' + existing['html_url'])
        return
    if existing is not None and existing_commit is None:
        require(existing['target_commitish'] == commit, 'Draft targets a different or unpinned commit')
    if existing_commit is None:
        # A draft does not create its pending Git tag. Pin it explicitly before
        # creating or resuming a draft, and never move an existing reference.
        gh('api', '--method', 'POST', f'repos/{REPOSITORY}/git/refs',
           '-f', 'ref=refs/tags/' + tag, '-f', 'sha=' + commit)
        require(remote_tag_commit(tag) == commit, 'Created Git reference does not match the checked build')
    if existing is None:
        gh('release', 'create', tag, '--repo', REPOSITORY, '--target', commit, '--draft',
           '--verify-tag', '--latest=false', '--title', '花有谱 ' + metadata['version'], '--notes-file', notes, *files)
    else:
        # Only an unpublished draft at this exact tag/commit may be completed.
        gh('release', 'upload', tag, '--repo', REPOSITORY, '--clobber', *files)
        gh('release', 'edit', tag, '--repo', REPOSITORY, '--notes-file', notes)
    require(remote_tag_commit(tag) == commit, 'Created tag does not match the checked build')
    with tempfile.TemporaryDirectory() as temp:
        gh('release', 'download', tag, '--repo', REPOSITORY, '--dir', temp)
        require({path.name for path in Path(temp).iterdir()} == {path.name for path in files},
                'Draft has unexpected assets; refusing to publish')
        for path in files:
            require((Path(temp) / path.name).read_bytes() == path.read_bytes(),
                    'Uploaded draft asset differs: ' + path.name)
    latest = api('releases/latest', missing_ok=True)
    previous = None
    if latest is not None:
        with tempfile.TemporaryDirectory() as temp:
            previous = release_metadata(latest['tag_name'], temp)
    promote = make_latest(metadata, previous)
    gh('release', 'edit', tag, '--repo', REPOSITORY, '--draft=false',
       '--latest=' + str(promote).lower())
    published = find_release(tag)
    require(published is not None, 'Published release could not be found')
    require(not published['draft'] and remote_tag_commit(tag) == commit, 'Release was not published correctly')
    print(published['html_url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    version = sub.add_parser('version')
    version.add_argument('--commit')
    version.add_argument('--development', action='store_true')
    version.add_argument('--github-output', type=Path)
    checks = sub.add_parser('wait-for-checks')
    checks.add_argument('--run-id', required=True)
    checks.add_argument('--commit', required=True)
    release = sub.add_parser('publish')
    release.add_argument('--directory', type=Path, required=True)
    release.add_argument('--commit', required=True)
    release.add_argument('--tag', required=True)
    release.add_argument('--notes', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'version':
        config = json.loads((ROOT / 'gradle/release-version.json').read_text())
        commit = args.commit or subprocess.check_output(
            ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        result = version_for_commit(ROOT, config, commit, args.development)
        if args.github_output:
            with args.github_output.open('a') as output:
                output.write(''.join(f'{key}={value}\n' for key, value in result.items()))
        print(json.dumps(result))
    elif args.command == 'wait-for-checks':
        wait_for_checks(args.run_id, args.commit)
    else:
        publish(args.directory, args.commit, args.tag, args.notes)


if __name__ == '__main__':
    main()
