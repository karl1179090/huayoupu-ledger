import argparse
import contextlib
import io
import datetime as dt
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

SPEC = importlib.util.spec_from_file_location('widget_host', Path(__file__).parents[1] / 'verify-widget-host-lifecycle.py')
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)


class WidgetHostLifecycleTest(unittest.TestCase):
    def host(self, directory, serial='emulator-5554'):
        return HOST.HostRun(argparse.Namespace(output=Path(directory), serial=serial, app_apk=Path('debug.apk'), test_apk=Path('test.apk')))

    def test_physical_serial_is_rejected_before_device_or_clock_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            host = self.host(directory, 'R3CN-synthetic-phone')
            host.adb = Mock()
            with self.assertRaises(ValueError):
                host.execute()
            host.adb.assert_not_called()

    def test_emulator_name_without_qemu_never_reaches_root_install_or_clock(self):
        with tempfile.TemporaryDirectory() as directory:
            host = self.host(directory)
            host.adb = Mock(return_value='0')
            with self.assertRaises(AssertionError):
                host.execute()
            host.adb.assert_called_once_with('shell', 'getprop', 'ro.kernel.qemu')

    def test_leaf_accessibility_nodes_are_successful_wait_results(self):
        with tempfile.TemporaryDirectory() as directory:
            host = self.host(directory)
            leaf = ET.Element('node')
            self.assertIs(leaf, host.wait('leaf', lambda: leaf, timeout=.1))

    def test_expected_financial_periods_change_at_real_local_midnight_and_keep_future_posted(self):
        with tempfile.TemporaryDirectory() as directory:
            host = self.host(directory)
            host.zone = ZoneInfo('Asia/Shanghai')
            at = lambda value: dt.datetime.fromisoformat(value).replace(tzinfo=host.zone).timestamp()
            host.fixture = {'records': [dict(at=int(at(day+'T12:00:00')*1000), cents=str(cents)) for day, cents in [
                ('2026-12-31', 12300), ('2027-01-01', 45600), ('2027-01-03', 700), ('2027-01-04', 900)]]}
            self.assertEqual(12300, host.expected('YEAR', at('2026-12-31T22:00:00')))
            self.assertEqual(47200, host.expected('YEAR', at('2027-01-01T00:15:00')))
            self.assertEqual(47200, host.expected('MONTH', at('2027-01-01T00:15:00')))
            self.assertEqual(58600, host.expected('WEEK', at('2027-01-03T22:00:00')))
            self.assertEqual(900, host.expected('WEEK', at('2027-01-04T00:15:00')))

    def test_clock_restore_failure_still_restores_auto_time_and_adbd_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            host = self.host(directory)
            host.original = dict(epoch=1793952000, monotonic=0, uid='2000', autoTime='1', autoZone='null')
            host.system_changed = True
            host.adbd_attempted = True
            host.root = Mock()
            host.clock = Mock(side_effect=RuntimeError('injected unsupported date command'))
            host.adb = Mock(return_value='')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, host.finish('injected failure'))
            host.adb.assert_any_call('shell', 'settings', 'put', 'global', 'auto_time', '1')
            host.adb.assert_any_call('shell', 'settings', 'delete', 'global', 'auto_time_zone')
            host.adb.assert_any_call('unroot')

    def test_partial_adb_root_transport_failure_still_restores_adbd_without_clock_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            host = self.host(directory)
            host.original = dict(epoch=1793952000, monotonic=0, uid='2000', autoTime='1', autoZone='1')
            def adb(*command, **kwargs):
                if command[0] == 'wait-for-device':
                    raise RuntimeError('injected transport failure after adbd restarted')
                return ''
            host.adb = Mock(side_effect=adb)
            with self.assertRaises(RuntimeError):
                host.root()
            self.assertTrue(host.adbd_attempted)
            self.assertFalse(host.system_changed)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, host.finish('partial root failure'))
            host.adb.assert_any_call('unroot')

    def test_wrong_apk_package_is_rejected_before_root_or_install(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            host = self.host(directory)
            host.args.app_apk = Path(directory) / 'debug.apk';host.args.app_apk.touch()
            host.args.test_apk = Path(directory) / 'test.apk';host.args.test_apk.touch()
            aapt = Path(directory) / 'build-tools/35.0.0/aapt2';aapt.parent.mkdir(parents=True);aapt.touch()
            host.adb = Mock(side_effect=['1', 'sdk_gphone64_arm64'])
            host.root = Mock()
            host.run = Mock(side_effect=["package: name='net.ankio.ledger'\napplication-debuggable", "package: name='unrelated.synthetic.app'\napplication-debuggable"])
            with patch.dict(HOST.os.environ, ANDROID_HOME=directory), self.assertRaises(AssertionError):
                host.execute()
            host.root.assert_not_called()
            self.assertEqual(2, host.adb.call_count)

    def test_failed_package_query_is_not_treated_as_an_absent_target(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            host = self.host(directory)
            host.args.app_apk = Path(directory) / 'debug.apk';host.args.app_apk.touch()
            host.args.test_apk = Path(directory) / 'test.apk';host.args.test_apk.touch()
            aapt = Path(directory) / 'build-tools/35.0.0/aapt2';aapt.parent.mkdir(parents=True);aapt.touch()
            host.adb = Mock(side_effect=['1', 'sdk_gphone64_arm64'])
            host.root = Mock()
            host.run = Mock(side_effect=["package: name='net.ankio.ledger'\napplication-debuggable", "package: name='net.ankio.ledger.test'\napplication-debuggable"])
            with patch.dict(HOST.os.environ, ANDROID_HOME=directory), patch.object(HOST.FIXTURE_SAFETY, 'installed_paths', side_effect=RuntimeError('device offline')):
                with self.assertRaises(RuntimeError):
                    host.execute()
            host.root.assert_not_called()
            self.assertEqual(2, host.adb.call_count)
