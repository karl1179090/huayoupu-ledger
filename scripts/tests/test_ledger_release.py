import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import hashlib

SPEC = importlib.util.spec_from_file_location('ledger_release', Path(__file__).parents[1] / 'ledger-release.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
COMMIT = 'a' * 40


class VersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Release test')
        self.git('config', 'user.email', 'synthetic@example.invalid')
        self.baseline = self.commit()
        self.config = dict(baselineCommit=self.baseline, baselineVersionCode=97,
                           baselinePatch=91, major=0, minor=5, suffix='-v6')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True, stderr=subprocess.DEVNULL).strip()

    def commit(self):
        self.git('commit', '--allow-empty', '-m', 'synthetic')
        return self.git('rev-parse', 'HEAD')

    def test_first_main_commit_and_rerun_have_identical_next_version(self):
        head = self.commit()
        first = MODULE.version_for_commit(self.root, self.config, head)
        self.assertEqual((98, '0.5.92-v6', 'v0.5.92-v6'),
                         (first['versionCode'], first['versionName'], first['tag']))
        self.assertEqual(first, MODULE.version_for_commit(self.root, self.config, head))
        next_version = MODULE.version_for_commit(self.root, self.config, self.commit())
        self.assertEqual(99, next_version['versionCode'])

    def test_feature_commits_count_as_one_main_merge(self):
        self.git('checkout', '-b', 'feature')
        for _ in range(3):
            self.commit()
        self.git('checkout', 'main')
        self.git('merge', '--no-ff', 'feature', '-m', 'merge synthetic feature')
        result = MODULE.version_for_commit(self.root, self.config, self.git('rev-parse', 'HEAD'))
        self.assertEqual(1, result['firstParentDistance'])
        self.assertEqual(98, result['versionCode'])

    def test_non_main_development_build_needs_no_baseline_history(self):
        result = MODULE.version_for_commit(self.root / 'absent', self.config, COMMIT, development=True)
        self.assertEqual((97, '0.5.91-v6'), (result['versionCode'], result['versionName']))

    def test_missing_or_side_parent_baseline_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'first-parent'):
            MODULE.version_for_commit(self.root, dict(self.config, baselineCommit=COMMIT), self.baseline)
        self.git('checkout', '-b', 'feature')
        side = self.commit()
        self.git('checkout', 'main')
        self.git('merge', '--no-ff', 'feature', '-m', 'merge')
        with self.assertRaisesRegex(ValueError, 'first-parent'):
            MODULE.version_for_commit(self.root, dict(self.config, baselineCommit=side),
                                      self.git('rev-parse', 'HEAD'))

    def test_android_code_overflow_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Android version'):
            MODULE.version_for_commit(self.root, dict(self.config, baselineVersionCode=2_100_000_000), self.commit())


class BuildGateTest(unittest.TestCase):
    def setUp(self):
        self.run = dict(repository={'full_name': MODULE.REPOSITORY},
                        head_repository={'full_name': MODULE.REPOSITORY},
                        path='.github/workflows/android.yml', event='push',
                        head_branch='main', head_sha=COMMIT, status='in_progress')
        self.jobs = [dict(id=index, name=name, status='completed', conclusion='success', run_attempt=1)
                     for index, name in enumerate(sorted(MODULE.REQUIRED_JOBS), 1)]

    def test_successful_checks_do_not_wait_for_own_publish_job(self):
        self.jobs.append(dict(id=9, name='publish / release', status='in_progress', conclusion=None))
        self.assertTrue(MODULE.run_checks_ready(self.run, self.jobs, COMMIT))

    def test_wrong_commit_branch_workflow_event_and_repository_are_rejected(self):
        bad = [dict(head_sha='b' * 40), dict(head_branch='feature'), dict(event='pull_request'),
               dict(path='.github/workflows/other.yml'),
               dict(repository={'full_name': 'other/repo'}),
               dict(head_repository={'full_name': 'other/repo'})]
        for changes in bad:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                MODULE.run_checks_ready(self.run | changes, self.jobs, COMMIT)

    def test_failed_skipped_or_cancelled_check_blocks_release(self):
        for conclusion in ['failure', 'skipped', 'cancelled', 'timed_out']:
            jobs = copy.deepcopy(self.jobs)
            jobs[0]['conclusion'] = conclusion
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                MODULE.run_checks_ready(self.run, jobs, COMMIT)

    def test_latest_attempt_cannot_reuse_older_success(self):
        failed = self.jobs[0] | dict(id=20, run_attempt=2, conclusion='failure')
        with self.assertRaises(ValueError):
            MODULE.run_checks_ready(self.run, self.jobs + [failed], COMMIT)
        old_failure = self.jobs[0] | dict(id=0, conclusion='failure')
        self.assertTrue(MODULE.run_checks_ready(self.run, [old_failure] + self.jobs, COMMIT))

    def test_missing_or_running_gate_waits_only_while_build_is_active(self):
        self.assertFalse(MODULE.run_checks_ready(self.run, self.jobs[:-1], COMMIT))
        running = copy.deepcopy(self.jobs)
        running[0].update(status='in_progress', conclusion=None)
        self.assertFalse(MODULE.run_checks_ready(self.run, running, COMMIT))
        with self.assertRaises(ValueError):
            MODULE.run_checks_ready(self.run | dict(status='completed'), self.jobs[:-1], COMMIT)


def metadata():
    return dict(commit=COMMIT, version='0.5.92-v6', versionCode=98, package='net.ankio.ledger',
                abi='arm64-v8a', signed=True, filename='Huayoupu-Ledger-0.5.92-v6-arm64-v8a.apk',
                sha256=hashlib.sha256(b'synthetic signed APK').hexdigest())


class ReleasePolicyTest(unittest.TestCase):
    def test_unsigned_wrong_version_source_and_file_escape_are_rejected(self):
        MODULE.validate_metadata(metadata(), COMMIT, 'v0.5.92-v6')
        for changes in [dict(signed=False), dict(version='0.5.91-v6'), dict(commit='b' * 40),
                        dict(abi='x86_64'), dict(filename='../outside-arm64-v8a.apk'), dict(versionCode=True)]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                MODULE.validate_metadata(metadata() | changes, COMMIT, 'v0.5.92-v6')

    def test_late_old_build_cannot_move_latest_backwards(self):
        current = metadata()
        self.assertFalse(MODULE.make_latest(current | dict(versionCode=97), current))
        self.assertTrue(MODULE.make_latest(current | dict(versionCode=99), current))
        self.assertTrue(MODULE.make_latest(current, None))
        with self.assertRaises(ValueError):
            MODULE.make_latest(current | dict(commit='b' * 40), current)

    def artifact_directory(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        info = metadata()
        for name, data in [(info['filename'], b'synthetic signed APK'),
                           ('Huayoupu-Ledger-source.zip', b'synthetic checked source')]:
            (root / name).write_bytes(data)
            (root / (name[:-4] + '.sha256' if name.endswith('.zip') else name + '.sha256')).write_text(
                hashlib.sha256(data).hexdigest() + '  ' + name + '\n')
        (root / 'build-info.json').write_text(json.dumps(info))
        return root

    def test_existing_published_commit_is_noop_without_upload_or_edit(self):
        directory = self.artifact_directory()
        assets = [{'name': p.name} for p in directory.iterdir()]
        published = dict(draft=False, assets=assets, html_url='https://example.invalid/release')
        with patch.object(MODULE, 'remote_tag_commit', return_value=COMMIT), \
             patch.object(MODULE, 'find_release', return_value=published), \
             patch.object(MODULE, 'release_metadata', return_value=metadata()), \
             patch.object(MODULE, 'gh') as gh:
            MODULE.publish(directory, COMMIT, 'v0.5.92-v6', directory / 'notes')
            gh.assert_not_called()

    def test_existing_tag_at_another_commit_cannot_be_overwritten(self):
        directory = self.artifact_directory()
        with patch.object(MODULE, 'remote_tag_commit', return_value='b' * 40), \
             patch.object(MODULE, 'gh') as gh, self.assertRaisesRegex(ValueError, 'different commit'):
            MODULE.publish(directory, COMMIT, 'v0.5.92-v6', directory / 'notes')
        gh.assert_not_called()

    def test_uploaded_bytes_must_match_before_draft_becomes_public(self):
        directory = self.artifact_directory()
        calls = []

        def fake_gh(*args, **kwargs):
            calls.append(args)
            if args[:2] == ('release', 'download'):
                target = Path(args[args.index('--dir') + 1])
                for original in directory.iterdir():
                    (target / original.name).write_bytes(b'wrong uploaded bytes')
            return ''

        with patch.object(MODULE, 'remote_tag_commit', side_effect=[None, COMMIT, COMMIT]), \
             patch.object(MODULE, 'find_release', return_value=None), \
             patch.object(MODULE, 'api', return_value=None), \
             patch.object(MODULE, 'gh', side_effect=fake_gh), \
             self.assertRaisesRegex(ValueError, 'Uploaded draft asset differs'):
            MODULE.publish(directory, COMMIT, 'v0.5.92-v6', directory / 'notes')
        self.assertFalse(any('--draft=false' in call for call in calls))


class ReleaseProtocolTest(unittest.TestCase):
    """Model the actual pending-tag/draft REST and CLI contracts."""
    artifact_directory = ReleasePolicyTest.artifact_directory
    def fake_github(self, directory, pending_draft=False):
        tag = 'v0.5.92-v6'
        state = {'tag': None, 'creates': 0, 'uploads': 0, 'release': None, 'calls': []}
        if pending_draft:
            state['release'] = dict(id=1, tag_name=tag, target_commitish=COMMIT, draft=True,
                                    assets=[], html_url='https://example.invalid/release')

        def fake_gh(*args, **kwargs):
            state['calls'].append(args)
            if args[0] == 'api':
                if '--method' in args:
                    self.assertIn('ref=refs/tags/' + tag, args)
                    self.assertIn('sha=' + COMMIT, args)
                    self.assertIsNone(state['tag'])
                    state['tag'] = COMMIT
                    return '{}'
                endpoint = args[1]
                if '/git/ref/tags/' in endpoint:
                    return None if state['tag'] is None else json.dumps(
                        {'object': {'type': 'commit', 'sha': state['tag']}})
                if '/releases?per_page=' in endpoint:
                    return json.dumps([[state['release']] if state['release'] else []])
                if endpoint.endswith('/releases/latest'):
                    return None
                if '/releases/tags/' in endpoint:
                    # Published-only endpoint must never be used to find a draft.
                    return None if state['release'] is None or state['release']['draft'] else json.dumps(state['release'])
                self.fail('Unexpected endpoint ' + endpoint)
            self.assertEqual('release', args[0])
            if args[1] == 'create':
                self.assertIsNone(state['release'], 'A pending draft already exists')
                self.assertEqual(COMMIT, state['tag'], 'Git ref must be pinned before creating a draft')
                self.assertIn('--verify-tag', args)
                state['creates'] += 1
                state['release'] = dict(id=1, tag_name=tag, target_commitish=COMMIT, draft=True,
                                        assets=[], html_url='https://example.invalid/release')
            elif args[1] == 'upload':
                self.assertTrue(state['release']['draft'])
                state['uploads'] += 1
            elif args[1] == 'download':
                self.assertTrue(state['release']['draft'])
                target = Path(args[args.index('--dir') + 1])
                for original in directory.iterdir():
                    (target / original.name).write_bytes(original.read_bytes())
            elif args[1] == 'edit':
                if '--draft=false' in args:
                    self.assertEqual(COMMIT, state['tag'])
                    state['release']['draft'] = False
            else:
                self.fail('Unexpected release operation')
            return ''
        return state, fake_gh

    def test_first_publish_pins_tag_before_creating_and_verifying_draft(self):
        directory = self.artifact_directory()
        state, fake = self.fake_github(directory)
        with patch.object(MODULE, 'gh', side_effect=fake):
            MODULE.publish(directory, COMMIT, 'v0.5.92-v6', directory / 'notes')
        self.assertEqual(1, state['creates'])
        self.assertFalse(state['release']['draft'])

    def test_retry_finds_pending_draft_in_list_and_resumes_without_create(self):
        directory = self.artifact_directory()
        state, fake = self.fake_github(directory, pending_draft=True)
        with patch.object(MODULE, 'gh', side_effect=fake):
            MODULE.publish(directory, COMMIT, 'v0.5.92-v6', directory / 'notes')
        self.assertEqual(0, state['creates'])
        self.assertEqual(1, state['uploads'])
        self.assertFalse(state['release']['draft'])

    def test_pending_draft_at_an_unpinned_other_commit_is_not_mutated(self):
        directory = self.artifact_directory()
        state, fake = self.fake_github(directory, pending_draft=True)
        state['release']['target_commitish'] = 'b' * 40
        with patch.object(MODULE, 'gh', side_effect=fake), self.assertRaisesRegex(ValueError, 'unpinned commit'):
            MODULE.publish(directory, COMMIT, 'v0.5.92-v6', directory / 'notes')
        self.assertIsNone(state['tag'])
        self.assertFalse(any('--method' in call for call in state['calls']))


if __name__ == '__main__':
    unittest.main()
