import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import subprocess

SPEC = importlib.util.spec_from_file_location('payment_fixture_builder', Path(__file__).parents[1] / 'build-payment-fixtures.py')
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class PaymentFixtureInstallationTest(unittest.TestCase):
    def test_absent_package_is_distinct_from_a_failed_device_query(self):
        with patch.object(BUILDER.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', '')):
            self.assertEqual([], BUILDER.installed_paths('emulator-5554', 'com.tencent.mm'))
        with patch.object(BUILDER.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'device offline')):
            with self.assertRaises(RuntimeError):
                BUILDER.installed_paths('emulator-5554', 'com.tencent.mm')

    def test_physical_or_implicit_serial_is_rejected_before_any_adb_command(self):
        with patch.object(BUILDER, 'run') as command:
            for serial in ['R3CN-synthetic-phone', '', '5554', 'emulator-5554;other']:
                with self.assertRaises(ValueError):
                    BUILDER.require_emulator(serial)
            command.assert_not_called()

    def test_emulator_name_alone_is_insufficient_without_qemu_property(self):
        with patch.object(BUILDER, 'run', return_value='0\n'):
            with self.assertRaises(ValueError):
                BUILDER.require_emulator('emulator-5554')
        with patch.object(BUILDER, 'run', return_value='1\n') as command:
            BUILDER.require_emulator('emulator-5554')
            self.assertEqual(['adb', '-s', 'emulator-5554', 'shell', 'getprop', 'ro.kernel.qemu'], command.call_args.args[0])

    def test_existing_package_requires_marker_prefix_and_the_local_fixture_certificate(self):
        package = 'com.tencent.mm'
        badge = f"package: name='{package}' versionName='{BUILDER.PREFIX}1'"
        for badging, tree, cert, accepted in [
            (badge, BUILDER.MARKER, 'expected', True),
            (badge, '', 'expected', False),
            ("package: name='com.tencent.mm' versionName='real-version'", BUILDER.MARKER, 'expected', False),
            (badge, BUILDER.MARKER, 'another-certificate', False),
        ]:
            with patch.object(BUILDER, 'run', side_effect=[badging, tree]), patch.object(BUILDER, 'certificate', return_value=cert):
                self.assertEqual(accepted, BUILDER.is_signed_fixture('aapt2', 'apksigner', 'base.apk', package, 'expected'))
