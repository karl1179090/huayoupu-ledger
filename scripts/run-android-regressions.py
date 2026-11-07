#!/usr/bin/env python3
"""Run the checked class manifest on one isolated emulator, preserving each run's reports."""
import argparse
import collections
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def read_results(directory, expected_classes):
    counts = collections.Counter()
    passed_counts = collections.Counter()
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in sorted(directory.glob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(root.get(key, '0'))
        for case in root.iter('testcase'):
            name = case.get('classname')
            counts[name] += 1
            if all(case.find(kind) is None for kind in ['failure', 'error', 'skipped']):
                passed_counts[name] += 1
    totals['passed'] = totals['tests'] - totals['failures'] - totals['errors'] - totals['skipped']
    missing = sorted(set(expected_classes) - set(counts))
    without_pass = sorted(name for name in expected_classes if passed_counts[name] == 0)
    successful = bool(totals['passed'] > 0 and not missing and not without_pass and not totals['failures'] and not totals['errors'])
    return dict(totals=totals, classes=dict(counts), passedClasses=dict(passed_counts),
                missingClasses=missing, classesWithoutPassedTests=without_pass, successful=successful)


def main():
    suites = json.loads((ROOT / 'scripts/android-regression-classes.json').read_text())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('suite', choices=suites)
    parser.add_argument('--abi', default='x86_64', choices=['x86_64', 'arm64-v8a'])
    parser.add_argument('--init-script', type=Path)
    parser.add_argument('--app-build-dir', type=Path, default=ROOT / 'app/build')
    parser.add_argument('--archive-dir', type=Path)
    parser.add_argument('--widget-timing', choices=['strict', 'record'], default='strict',
                        help='Enforce the 2s widget target (default), or record timings on shared CI emulators.')
    parser.add_argument('--list', action='store_true')
    args = parser.parse_args()
    classes = suites[args.suite]
    assert classes and len(classes) == len(set(classes)), 'Empty or duplicate class list'
    for name in classes:
        path = ROOT / 'app/src/androidTest/java' / (name.replace('.', '/') + '.kt')
        if not path.is_file():
            parser.error(f'Listed Android test does not exist: {name}')
    if args.list:
        print('\n'.join(classes))
        return 0
    serial = os.environ.get('ANDROID_SERIAL')
    if serial is None:
        devices = subprocess.check_output(['adb', 'devices'], text=True)
        emulators = re.findall(r'^(emulator-\d+)\s+device$', devices, re.M)
        if len(emulators) != 1:
            parser.error('Set ANDROID_SERIAL to one isolated emulator explicitly')
        serial = emulators[0]
    if not re.fullmatch(r'emulator-\d+', serial):
        parser.error('This synthetic regression entry only accepts an emulator serial')
    if subprocess.check_output(['adb', '-s', serial, 'shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() != '1':
        parser.error('The selected device is not an Android emulator')
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    archive = (args.archive_dir or ROOT / 'output/android-regressions' / f'{args.suite}-{stamp}').resolve()
    if (archive / 'run.json').exists() or (archive / 'results').exists():
        parser.error('Choose a fresh archive directory; previous run evidence is retained')
    archive.mkdir(parents=True, exist_ok=True)
    build = args.app_build_dir.resolve()
    generated = {
        'results': build / 'outputs/androidTest-results/connected/debug',
        'report': build / 'reports/androidTests/connected/debug',
    }
    # Only generated connected-test directories are cleared, after retaining their previous contents.
    for kind, path in generated.items():
        if path.exists():
            shutil.copytree(path, archive / f'previous-{stamp}' / kind, dirs_exist_ok=True)
            shutil.rmtree(path)
    command = ['bash', str(ROOT / 'gradlew'), '-PledgerPython=python3', f'-PledgerAbi={args.abi}',
               '-Pandroid.testInstrumentationRunnerArguments.class=' + ','.join(classes),
               '-Pandroid.testInstrumentationRunnerArguments.ledgerWidgetTiming=' + args.widget_timing]
    if args.init_script:
        command += ['--init-script', str(args.init_script.resolve())]
    command += [':app:connectedDebugAndroidTest', '--stacktrace', '--console=plain']
    env = dict(os.environ, ANDROID_SERIAL=serial)
    result = subprocess.run(command, cwd=ROOT, env=env)
    for kind, path in generated.items():
        if path.exists():
            shutil.copytree(path, archive / kind, dirs_exist_ok=True)
    summary = read_results(archive / 'results', classes)
    metadata = dict(suite=args.suite, serial=serial, abi=args.abi, command=command, widgetTiming=args.widget_timing,
                    startedAt=stamp, exitCode=result.returncode, **summary)
    (archive / 'run.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(dict(archive=str(archive), **summary)))
    return result.returncode or (0 if summary['successful'] else 1)


if __name__ == '__main__':
    sys.exit(main())
