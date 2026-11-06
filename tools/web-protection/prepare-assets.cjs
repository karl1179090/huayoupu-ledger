'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const [sourceArgument, outputArgument, mode] = process.argv.slice(2);
if (!sourceArgument || !outputArgument || !['debug', 'release'].includes(mode)) {
  throw new Error('Usage: node prepare-assets.cjs <source-assets> <generated-output> <debug|release>');
}
// Resolve existing ancestors as well, so a generated-directory symlink cannot reach source files.
function canonicalPath(value) {
  let existing = path.resolve(value);
  const missing = [];
  while (!fs.existsSync(existing)) {
    if (fs.lstatSync(existing, { throwIfNoEntry: false })?.isSymbolicLink()) {
      throw new Error('Dangling symlink is not a valid generated output');
    }
    missing.unshift(path.basename(existing));
    const parent = path.dirname(existing);
    if (parent === existing) throw new Error('Cannot resolve generated output root');
    existing = parent;
  }
  return path.resolve(fs.realpathSync(existing), ...missing);
}
const source = canonicalPath(sourceArgument);
const output = canonicalPath(outputArgument);
const explicitBuildRoot = process.env.LEDGER_GENERATED_BUILD_ROOT;
if (explicitBuildRoot && !path.isAbsolute(explicitBuildRoot)) throw new Error('Generated build root must be absolute');
const buildRoot = canonicalPath(explicitBuildRoot || path.resolve(source, '../../..', 'build'));
// Build cleanup is restricted to an explicit generated output directory, never the source tree.
const relative = path.relative(buildRoot, output).split(path.sep);
const allowedOutput = relative.length === 3 && relative[0] === 'generated' &&
  (relative[1] === 'ledgerAssets' && /^[A-Za-z0-9_-]+$/.test(relative[2]) ||
   relative[1] === 'assets' && /^prepare[A-Za-z0-9]+LedgerAssets$/.test(relative[2]));
if (!allowedOutput || !output.startsWith(buildRoot + path.sep) ||
    buildRoot === source || source.startsWith(buildRoot + path.sep) || buildRoot.startsWith(source + path.sep) ||
    output === source || source.startsWith(output + path.sep) || output.startsWith(source + path.sep)) {
  throw new Error(`Output must be a separate generated/ledgerAssets/<variant> directory: ${source} -> ${output}`);
}
fs.rmSync(output, { recursive: true, force: true });
fs.cpSync(source, output, { recursive: true });
// Both engine variants ship the exact project license beside third-party notices.
fs.copyFileSync(path.resolve(source, '../../../../LICENSE'), path.join(output, 'licenses', 'project-GPL-3.0.txt'));
if (mode === 'release') {
  const JavaScriptObfuscator = require('javascript-obfuscator');
  fs.copyFileSync(path.join(__dirname, 'LICENSE.javascript-obfuscator.BSD'),
    path.join(output, 'web', 'vendor', 'javascript-obfuscator-LICENSE.txt'));
  // Top-level functions and property names are a public API: inline onclick, WebView
  // evaluateJavascript and the Java bridge all depend on their exact names.
  for (const file of ['app.js', 'permission.js', 'fluid-motion.js']) {
    const target = path.join(output, 'web', file);
    const original = fs.readFileSync(target, 'utf8');
    const transformed = JavaScriptObfuscator.obfuscate(original, {
      target: 'browser-no-eval',
      seed: 61019,
      compact: true,
      simplify: true,
      renameGlobals: false,
      renameProperties: false,
      identifierNamesGenerator: 'hexadecimal',
      identifiersPrefix: `hm_${file.replace(/[^A-Za-z0-9]/g, '_')}_`,
      stringArray: true,
      stringArrayThreshold: 0.35,
      stringArrayEncoding: [],
      stringArrayCallsTransform: false,
      stringArrayIndexShift: false,
      stringArrayRotate: false,
      stringArrayShuffle: false,
      splitStrings: false,
      controlFlowFlattening: false,
      deadCodeInjection: false,
      selfDefending: false,
      debugProtection: false,
      disableConsoleOutput: false,
      sourceMap: false,
      reservedNames: ['^LedgerNative$', '^LedgerUi$', '^PermissionUi$', '^window$', '^document$'],
    }).getObfuscatedCode();
    new vm.Script(transformed, { filename: file });
    fs.writeFileSync(target, transformed, 'utf8');
    process.stdout.write(`Protected ${file}: ${Buffer.byteLength(original)} -> ${Buffer.byteLength(transformed)} bytes\n`);
  }
}
