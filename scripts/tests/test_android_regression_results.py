import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location('android_regression_runner', Path(__file__).parents[1] / 'run-android-regressions.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class AndroidRegressionResultsTest(unittest.TestCase):
    def result(self, cases, classes):
        with tempfile.TemporaryDirectory() as directory:
            root = ET.Element('testsuite', tests=str(len(cases)), failures=str(sum(status=='failure' for _, status in cases)),
                              errors='0', skipped=str(sum(status=='skipped' for _, status in cases)))
            for i, (name, status) in enumerate(cases):
                case = ET.SubElement(root, 'testcase', classname=name, name=f'case{i}')
                if status:
                    ET.SubElement(case, status).text = 'Explicit synthetic condition'
            ET.ElementTree(root).write(Path(directory) / 'TEST-current.xml')
            return RUNNER.read_results(Path(directory), classes)

    def test_absent_external_fixture_cannot_turn_thirty_six_assumptions_into_pass(self):
        result = self.result([('Wechat', 'skipped')]*22 + [('Window', 'skipped')]*14, ['Wechat', 'Window'])
        self.assertFalse(result['successful'])
        self.assertEqual(0, result['totals']['passed'])
        self.assertEqual(36, result['totals']['skipped'])
        self.assertEqual(['Wechat', 'Window'], result['classesWithoutPassedTests'])

    def test_one_passing_class_cannot_hide_another_mandatory_class_with_only_skips(self):
        result = self.result([('A', None), ('B', 'skipped')], ['A', 'B'])
        self.assertFalse(result['successful'])
        self.assertEqual(['B'], result['classesWithoutPassedTests'])

    def test_partial_version_assumption_is_reported_separately_from_the_pass(self):
        result = self.result([('A', None), ('A', 'skipped')], ['A'])
        self.assertTrue(result['successful'])
        self.assertEqual(1, result['totals']['passed'])
        self.assertEqual(1, result['totals']['skipped'])
        self.assertEqual({'A': 1}, result['passedClasses'])

    def test_failure_or_missing_mandatory_class_is_never_successful(self):
        self.assertFalse(self.result([('A', None), ('A', 'failure')], ['A'])['successful'])
        self.assertFalse(self.result([('A', None)], ['A', 'B'])['successful'])
