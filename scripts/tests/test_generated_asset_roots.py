from pathlib import Path
import importlib.util
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch


class GeneratedAssetRootsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ledger-generated-root-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.project = self.base / 'project'
        self.assets = self.project / 'app/src/main/assets'
        (self.assets / 'licenses').mkdir(parents=True)
        (self.assets / 'sentinel.txt').write_text('preserve source', encoding='utf-8')
        (self.project / 'LICENSE').write_text('synthetic license fixture', encoding='utf-8')
        self.external = self.base / 'external-build'
        self.repo = Path(__file__).resolve().parents[2]
        spec = importlib.util.spec_from_file_location('ledger_v6_roots', self.repo / 'scripts/prepare-ledger-v6-assets.py')
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.module.ROOT = self.project

    def node(self, output, build_root=None):
        environment = dict(os.environ)
        environment.pop('LEDGER_GENERATED_BUILD_ROOT', None)
        if build_root is not None:
            environment['LEDGER_GENERATED_BUILD_ROOT'] = str(build_root)
        return subprocess.run(['node', str(self.repo / 'tools/web-protection/prepare-assets.cjs'),
                               str(self.assets), str(output), 'debug'], env=environment,
                              capture_output=True, text=True)

    def python(self, output, build_root=None):
        with patch.dict(os.environ):
            os.environ.pop('LEDGER_GENERATED_BUILD_ROOT', None)
            if build_root is not None:
                os.environ['LEDGER_GENERATED_BUILD_ROOT'] = str(build_root)
            return self.module.generated_output(output)

    def rejected(self, output, build_root=None):
        output.mkdir(parents=True, exist_ok=True)
        marker = output / 'keep.txt'
        marker.write_text('do not clean', encoding='utf-8')
        self.assertNotEqual(0, self.node(output, build_root).returncode)
        with self.assertRaises(RuntimeError):
            self.python(output, build_root)
        self.assertEqual('do not clean', marker.read_text(encoding='utf-8'))
        self.assertEqual('preserve source', (self.assets / 'sentinel.txt').read_text(encoding='utf-8'))

    def test_default_build_generated_directory_remains_supported(self):
        output = self.project / 'app/build/generated/ledgerAssets/debug'
        self.assertEqual(0, self.node(output).returncode)
        self.assertEqual(output.resolve(), self.python(output))
        self.assertEqual('preserve source', (output / 'sentinel.txt').read_text(encoding='utf-8'))

    def test_explicit_external_project_build_directory_supports_agp_generated_assets(self):
        output = self.external / 'generated/assets/prepareDebugLedgerAssets'
        self.assertEqual(0, self.node(output, self.external).returncode)
        self.assertEqual(output.resolve(), self.python(output, self.external))
        self.assertTrue((output / 'licenses/project-GPL-3.0.txt').is_file())

    def test_external_output_without_explicit_root_is_rejected(self):
        self.rejected(self.external / 'generated/ledgerAssets/debug')

    def test_output_outside_explicit_root_is_rejected_before_cleaning(self):
        self.rejected(self.base / 'other/generated/ledgerAssets/debug', self.external)

    def test_source_tree_and_source_ancestor_roots_are_rejected(self):
        for root in [self.assets, self.project]:
            self.rejected(self.assets / 'generated/ledgerAssets/debug', root)

    def test_generated_parent_symlink_into_source_is_rejected(self):
        self.external.mkdir()
        (self.external / 'generated').symlink_to(self.assets, target_is_directory=True)
        self.rejected(self.external / 'generated/ledgerAssets/debug', self.external)

    def test_output_symlink_outside_root_is_rejected(self):
        outside = self.base / 'outside'
        outside.mkdir()
        output = self.external / 'generated/ledgerAssets/debug'
        output.parent.mkdir(parents=True)
        output.symlink_to(outside, target_is_directory=True)
        self.rejected(output, self.external)

    def test_relative_explicit_build_root_is_rejected(self):
        self.rejected(self.external / 'generated/ledgerAssets/debug', 'relative-build')


if __name__ == '__main__':
    unittest.main()
