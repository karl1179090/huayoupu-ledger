#!/usr/bin/env python3
"""Explicit isolated-emulator W10/W11/W15 run; never used against a physical device.

Uses the real system Launcher, a tiny instrumentation seed/verification phase, adb,
and Node 22's WebSocket CDP client. Requires a fresh synthetic app/Launcher state.
The emulator clock is temporarily changed and restored; no production sources change.
"""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'net.ankio.ledger'
PROBE = 'net.ankio.ledger.widget.ExpenseWidgetHostProbeTest'
FIXTURE_SPEC = importlib.util.spec_from_file_location('widget_fixture_safety', ROOT / 'scripts/build-payment-fixtures.py')
FIXTURE_SAFETY = importlib.util.module_from_spec(FIXTURE_SPEC)
FIXTURE_SPEC.loader.exec_module(FIXTURE_SAFETY)



class HostRun:
    def __init__(self, args):
        self.args = args
        self.out = args.output.resolve()
        self.out.mkdir(parents=True, exist_ok=True)
        if (self.out / 'run.json').exists():
            raise ValueError('Use a new output directory; prior evidence is retained')
        self.log = []
        self.checks = []
        self.fixture = None
        self.original = None
        self.run_token = str(uuid.uuid4())
        self.system_changed = False
        self.setup_attempted = False
        self.adbd_attempted = False

    def run(self, command, *, timeout=30, check=True):
        result = subprocess.run([str(v) for v in command], capture_output=True, text=True, timeout=timeout)
        self.log.append(dict(command=[str(v) for v in command], exitCode=result.returncode, stdout=result.stdout, stderr=result.stderr))
        if check and result.returncode:
            raise RuntimeError(f'Command failed: {command}: {result.stderr or result.stdout}')
        return result.stdout.strip()

    def adb(self, *command, **kwargs):
        return self.run(['adb', '-s', self.args.serial, *command], **kwargs)

    def wait(self, label, condition, timeout=20):
        end = time.monotonic() + timeout
        last = None
        while time.monotonic() < end:
            try:
                value = condition()
                if value is not None and value is not False:
                    return value
            except (ValueError, RuntimeError, ET.ParseError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
                last = str(error)
            time.sleep(.25)
        raise AssertionError(f'{label} timed out: {last}')

    def record(self, name, **facts):
        self.checks.append(dict(name=name, status='PASS', **facts))

    def file(self, relative):
        return self.adb('exec-out', 'run-as', PACKAGE, 'cat', relative)

    def preferences(self, name):
        return {node.attrib['name']: node.text if node.tag == 'string' else node.attrib.get('value')
                for node in ET.fromstring(self.file(f'shared_prefs/{name}.xml'))}

    def selection(self):
        return self.preferences('expense_widget_instances').get(f'period:{self.fixture["widgetId"]}')

    def snapshot(self):
        return json.loads(self.preferences('expense_widget_last_success')[str(self.fixture['widgetId'])])

    def now(self):
        return int(self.adb('shell', 'date', '+%s'))

    def range(self, period, at):
        local = dt.datetime.fromtimestamp(at, self.zone)
        date = local.date()
        if period == 'WEEK':
            first = date - dt.timedelta(days=date.weekday())
            end = first + dt.timedelta(days=7)
        elif period == 'MONTH':
            first = date.replace(day=1)
            end = dt.date(first.year + (first.month == 12), first.month % 12 + 1, 1)
        else:
            first, end = dt.date(date.year, 1, 1), dt.date(date.year + 1, 1, 1)
        midnight = lambda day: dt.datetime.combine(day, dt.time(), self.zone).timestamp()
        return int(midnight(first) * 1000), int(midnight(end) * 1000)

    def expected(self, period, at):
        start, end = self.range(period, at)
        return sum(int(row['cents']) for row in self.fixture['records'] if start <= row['at'] < end)

    def current(self, period, min_now=None):
        value = self.snapshot()
        now = self.now()
        if value['period'] != period or self.selection() != period:
            return False
        if self.range(period, value['now'] / 1000) != self.range(period, now):
            return False
        if int(value['total']) != self.expected(period, now):
            return False
        if min_now is not None and value['now'] < min_now:
            return False
        return value

    def tree(self):
        path = '/data/local/tmp/huayoupu-widget-host.xml'
        self.adb('shell', 'uiautomator', 'dump', path, timeout=20)
        return ET.fromstring(self.adb('exec-out', 'cat', path))

    def click(self, resource):
        def locate():
            return next((n for n in self.tree().iter('node') if n.get('resource-id', '').endswith('/' + resource)), None)
        node = self.wait(f'visible {resource}', locate)
        numbers = [int(v) for v in re.findall(r'\d+', node.attrib['bounds'])]
        assert len(numbers) == 4 and numbers[2] > numbers[0] and numbers[3] > numbers[1], node.attrib
        self.adb('shell', 'input', 'tap', str((numbers[0] + numbers[2]) // 2), str((numbers[1] + numbers[3]) // 2))

    def home(self):
        self.adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        self.adb('shell', 'input', 'keyevent', '82')
        self.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        self.wait('actual Launcher workspace', lambda: any(n.get('resource-id', '').endswith('/workspace') for n in self.tree().iter('node')))

    def capture(self, label):
        self.adb('shell', 'screencap', '-p', f'/data/local/tmp/huayoupu-widget-{label}.png')
        self.adb('pull', f'/data/local/tmp/huayoupu-widget-{label}.png', self.out / f'{label}.png')
        (self.out / f'{label}.xml').write_text(ET.tostring(self.tree(), encoding='unicode'))
        (self.out / f'{label}-snapshot.json').write_text(json.dumps(self.snapshot(), indent=2) + '\n')

    def instrument(self, phase):
        output = self.adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PROBE,
                          '-e', 'widgetHostPhase', phase, '-e', 'widgetHostToken', self.run_token, PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=60)
        (self.out / f'probe-{phase}.txt').write_text(output + '\n')
        assert 'OK (1 test)' in output and 'FAILURES!!!' not in output, output
        self.record('instrumentation-' + phase, tests=1, raw=f'probe-{phase}.txt')

    def pid(self, package=PACKAGE):
        value = self.adb('shell', 'pidof', package, check=False).split()
        return value[0] if value else None

    def kill_app(self):
        self.home()
        old = self.pid()
        if old:
            self.adb('shell', 'am', 'kill', PACKAGE)
            try:
                self.wait('background process killed', lambda: not self.pid(), timeout=4)
            except AssertionError:
                # Only the previously verified app process, never a reused/system PID.
                name = self.adb('shell', 'cat', f'/proc/{old}/cmdline').strip('\x00')
                assert name == PACKAGE, name
                self.adb('shell', 'kill', '-9', old)
                self.wait('app process absent', lambda: not self.pid())
        return old

    def refresh(self, period):
        self.click('widget_' + period.lower())
        return self.wait('fresh ' + period, lambda: self.current(period))

    def root(self):
        self.adbd_attempted = True
        self.adb('root')
        self.adb('wait-for-device', timeout=60)
        assert self.adb('shell', 'id', '-u') == '0', 'Official userdebug emulator with adb root is required for controlled clock tests'

    def clock(self, value):
        epoch = int(value.timestamp())
        # Android toybox date accepts @epoch; verify the observed result instead of trusting exit code.
        self.adb('shell', 'date', '-u', '-s', '@' + str(epoch))
        assert abs(self.now() - epoch) < 5

    def cold_refresh(self):
        self.refresh('YEAR')
        before = self.selection()
        previous = self.snapshot()['now']
        old_pid = self.kill_app()
        self.click('widget_refresh')
        snapshot = self.wait('cold database-only refresh', lambda: self.current('YEAR', previous + 1))
        new_pid = self.wait('new widget process', self.pid)
        assert old_pid != new_pid
        assert self.selection() == before == 'YEAR'
        activities = self.adb('shell', 'dumpsys', 'activity', 'activities')
        assert not re.search(r'(?:mResumedActivity|topResumedActivity).*net\.ankio\.ledger', activities)
        maps = self.adb('shell', 'cat', f'/proc/{new_pid}/maps')
        (self.out / 'cold-widget-process-maps.txt').write_text(maps + '\n')
        assert not re.search(r'lib(?:onnxruntime|ledger_ocr_ort|opencv_java4|paddle_light|paddle_lite)', maps), 'OCR native runtime loaded during pure widget refresh'
        self.record('W10-process-restart', beforePid=old_pid, afterPid=new_pid, period=before, total=snapshot['total'], noResumedActivity=True, ocrNativeLibrariesAbsent=True)
        self.capture('process-restart')

    def launcher_restart(self):
        tree = self.tree()
        launcher = next(n.get('package') for n in tree.iter('node') if n.get('resource-id', '').endswith('/workspace'))
        assert launcher != PACKAGE
        old = self.pid(launcher)
        self.adb('shell', 'am', 'force-stop', launcher)
        self.home()
        new = self.wait('new Launcher process', lambda: self.pid(launcher))
        self.click('widget_refresh')
        self.wait('selection after Launcher reconstruction', lambda: self.current('YEAR'))
        assert old != new
        (self.out / 'launcher-package.txt').write_text(self.adb('shell', 'dumpsys', 'package', launcher) + '\n')
        self.record('W10-launcher-reconstruction', package=launcher, beforePid=old, afterPid=new, sameWidgetId=self.fixture['widgetId'])
        self.capture('launcher-restart')

    def reboot(self):
        boot = self.adb('shell', 'cat', '/proc/sys/kernel/random/boot_id')
        self.adb('reboot')
        self.adb('wait-for-device', timeout=90)
        self.wait('new system boot identity', lambda: self.adb('shell', 'cat', '/proc/sys/kernel/random/boot_id') != boot, timeout=90)
        self.wait('system boot completed', lambda: self.adb('shell', 'getprop', 'sys.boot_completed') == '1', timeout=90)
        new = self.adb('shell', 'cat', '/proc/sys/kernel/random/boot_id')
        assert boot != new
        self.home()
        self.click('widget_refresh')
        self.wait('same persisted instance after system boot', lambda: self.current('YEAR'))
        self.record('W10-emulator-system-reboot', beforeBootId=boot, afterBootId=new, sameWidgetId=self.fixture['widgetId'], period=self.selection())
        self.capture('system-reboot')
        self.root()

    def boundary(self, label, period, before, after):
        self.clock(dt.datetime.fromisoformat(before).replace(tzinfo=self.zone))
        self.home();self.refresh(period)
        previous = self.snapshot()
        self.adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
        self.clock(dt.datetime.fromisoformat(after).replace(tzinfo=self.zone))
        self.home();self.click('widget_refresh')
        current = self.wait('actual clock-boundary refresh', lambda: self.current(period))
        assert self.range(period, previous['now'] / 1000) != self.range(period, current['now'] / 1000)
        assert previous['total'] != current['total'], 'Nonzero fixtures must distinguish the two periods'
        self.record('W11-' + label, period=period, before=previous, after=current, realDeviceClockChanged=True, sleptAndWoke=True)
        self.capture('boundary-' + label)

    def route(self, period, cold):
        self.home();self.refresh(period)
        expected = self.expected(period, self.now())
        old = self.kill_app() if cold else self.pid()
        self.click('widget_header')
        pid = self.wait('MainActivity process from widget PendingIntent', self.pid)
        if cold:
            assert pid != old
        else:
            assert pid == old, 'Warm route unexpectedly restarted the process'
        port = self.adb('forward', 'tcp:0', f'localabstract:webview_devtools_remote_{pid}')
        try:
            def read():
                raw = self.run(['node', ROOT / 'scripts/probe-widget-route.cjs', port], check=False, timeout=15)
                if not raw:
                    return False
                value = json.loads(raw)
                if value.get('page') == 'stats' and value.get('period') == period and value.get('cents') == str(expected):
                    return value
                return False
            value = self.wait('native→bridge→JS exact period and amount', read, timeout=30)
            label = f'route-{period.lower()}-{"cold" if cold else "warm"}'
            (self.out / f'{label}.json').write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
            self.record('W15-' + label, pid=pid, expectedCents=str(expected), nativeBridgeJavaScript=value)
            self.capture(label)
        finally:
            self.adb('forward', '--remove', 'tcp:' + port)

    def execute(self):
        if not re.fullmatch(r'emulator-\d+', self.args.serial):
            raise ValueError('An explicit emulator serial is required')
        assert self.adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
        assert self.adb('shell', 'getprop', 'ro.product.model').find('sdk_gphone') >= 0
        for path in [self.args.app_apk, self.args.test_apk]:
            assert path.is_file(), path
        sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
        aapt = sdk / 'build-tools/35.0.0/aapt2'
        assert aapt.is_file(), 'Prepare the existing aapt2 build tool first'
        for apk, expected_package in [(self.args.app_apk, PACKAGE), (self.args.test_apk, PACKAGE + '.test')]:
            badging = self.run([aapt, 'dump', 'badging', apk])
            declared = re.search(r"^package: name='([^']+)'", badging, re.M)
            assert declared and declared.group(1) == expected_package, f'Wrong APK package: {apk}'
            assert 'application-debuggable' in badging, 'Host probes require the reviewed debug APKs'
        # Reuse the fixture installer's distinction between a missing package and a failed query.
        assert not FIXTURE_SAFETY.installed_paths(self.args.serial, PACKAGE), 'Requires an absent target app after the isolated AGP uninstall; no existing ledger is wiped or opened'
        self.original = dict(epoch=self.now(), monotonic=time.monotonic(), uid=self.adb('shell', 'id', '-u'),
                             autoTime=self.adb('shell', 'settings', 'get', 'global', 'auto_time'),
                             autoZone=self.adb('shell', 'settings', 'get', 'global', 'auto_time_zone'))
        self.root()
        self.system_changed = True
        self.adb('shell', 'settings', 'put', 'global', 'auto_time', '0')
        self.adb('shell', 'settings', 'put', 'global', 'auto_time_zone', '0')
        self.zone = ZoneInfo(self.adb('shell', 'getprop', 'persist.sys.timezone') or 'GMT')
        self.clock(dt.datetime(2026, 12, 31, 22, 0, tzinfo=self.zone))
        self.adb('install', '-r', '-t', self.args.app_apk, timeout=60)
        self.adb('install', '-r', '-t', self.args.test_apk, timeout=60)
        self.setup_attempted = True
        self.instrument('setup')
        self.fixture = json.loads(self.file('cache/widget-host-fixture.json'))
        assert self.fixture['zone'] == str(self.zone)
        (self.out / 'fixture.json').write_text(json.dumps(self.fixture, ensure_ascii=False, indent=2) + '\n')
        self.home();self.cold_refresh();self.launcher_restart();self.reboot()
        self.boundary('month', 'MONTH', '2026-12-31T22:00:00', '2027-01-01T00:15:00')
        self.boundary('year', 'YEAR', '2026-12-31T22:00:00', '2027-01-01T00:15:00')
        self.boundary('week', 'WEEK', '2027-01-03T22:00:00', '2027-01-04T00:15:00')
        for period in ['WEEK', 'MONTH', 'YEAR']:
            self.route(period, cold=True)
            self.route(period, cold=False)
        self.instrument('verify')
        (self.out / 'financial-probe.json').write_text(self.file('cache/widget-host-final-probe.json') + '\n')

    def finish(self, failure):
        errors = []
        if self.setup_attempted:
            try:
                # Cleanup only the ownership-list rows; do not uninstall or wipe unrelated app data.
                self.instrument('cleanup')
            except Exception as error:
                errors.append('synthetic cleanup: ' + str(error))
        if self.original and self.system_changed:
            try:
                self.root()
                self.clock(dt.datetime.fromtimestamp(self.original['epoch'] + time.monotonic() - self.original['monotonic'], dt.timezone.utc))
            except Exception as error:
                errors.append('emulator clock restoration: ' + str(error))
            # Restore every setting even if the clock command itself failed.
            for key, source in [('auto_time', 'autoTime'), ('auto_time_zone', 'autoZone')]:
                try:
                    value = self.original[source]
                    self.adb('shell', 'settings', 'delete' if value == 'null' else 'put', 'global', key, *([] if value == 'null' else [value]))
                except Exception as error:
                    errors.append(key + ' restoration: ' + str(error))
        # adbd may already have restarted as root when transport/id verification fails.
        if self.original and self.adbd_attempted and self.original['uid'] != '0':
            try:
                self.adb('unroot')
                self.adb('wait-for-device', timeout=60)
                assert self.adb('shell', 'id', '-u') == self.original['uid'], 'adbd identity did not return to its entry uid'
            except Exception as error:
                errors.append('adbd uid restoration: ' + str(error))
        artifacts = {str(p.relative_to(self.out)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in self.out.rglob('*') if p.is_file() and p.name != 'run.json'}
        source_paths = [Path(__file__).resolve(), ROOT / 'scripts/probe-widget-route.cjs', ROOT / 'app/src/androidTest/java/net/ankio/ledger/widget/ExpenseWidgetHostProbeTest.kt']
        source_result = subprocess.run(['git', '-C', ROOT, 'rev-parse', 'HEAD'], capture_output=True, text=True)
        source_commit = source_result.stdout.strip() if source_result.returncode == 0 else None
        report = dict(format='Host checks with separately recorded instrumentation phases; not an AGP XML report', sourceCommit=source_commit,
                      inputSourceSHA256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_paths}, runToken=self.run_token, status='FAIL' if failure or errors else 'PASS', serial=self.args.serial,
                      apks={str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in [self.args.app_apk, self.args.test_apk] if p.is_file()},
                      checks=self.checks, failure=failure, cleanupErrors=errors, artifacts=artifacts, commands=self.log,
                      limitations='Controlled official emulator process/system clock tests; not real payment Apps, power loss, or third-party OEM Launchers')
        (self.out / 'run.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(dict(status=report['status'], output=str(self.out), checks=len(self.checks), cleanupErrors=errors), ensure_ascii=False))
        return 1 if failure or errors else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--app-apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    host = HostRun(args)
    failure = None
    try:
        host.execute()
    except Exception:
        failure = traceback.format_exc()
    return host.finish(failure)


if __name__ == '__main__':
    raise SystemExit(main())
