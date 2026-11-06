#!/usr/bin/env python3
"""Build synthetic external payment APKs; installation is opt-in and emulator-only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'huayoupu-synthetic-payment-fixture-'
MARKER = 'net.ankio.ledger.synthetic.payment_fixture'
COMPONENTS = {
    'com.tencent.mm': [
        'com.tencent.mm.ui.LauncherUI',
        'com.tencent.mm.BillActivity',
        'com.tencent.mm.plugin.remittance.ui.RemittanceResultNewUI',
        'com.tencent.mm.framework.app.UIPageFragmentActivity',
        'com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI',
        'com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI',
    ],
    'com.eg.android.AlipayGphone': [
        'com.alipay.mobile.quinox.LauncherActivity',
        'com.alipay.fixture.PaymentResultActivity',
    ],
}


def run(command, capture=False):
    return subprocess.check_output([str(v) for v in command], text=True) if capture else subprocess.run([str(v) for v in command], check=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def certificate(signer, path):
    result = run([signer, 'verify', '--print-certs', path], capture=True)
    match = re.search(r'certificate SHA-256 digest:\s*([0-9a-fA-F]+)', result)
    if not match:
        raise RuntimeError('Cannot verify synthetic APK certificate')
    return match.group(1).lower()


def require_emulator(serial):
    if not re.fullmatch(r'emulator-\d+', serial):
        raise ValueError('Installation requires an explicit emulator serial; physical devices are refused')
    if run(['adb', '-s', serial, 'shell', 'getprop', 'ro.kernel.qemu'], capture=True).strip() != '1':
        raise ValueError('ro.kernel.qemu is not 1; no package was installed')


def is_signed_fixture(aapt, signer, apk, package, expected_certificate):
    badging = run([aapt, 'dump', 'badging', apk], capture=True)
    tree = run([aapt, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk], capture=True)
    return (f"name='{package}'" in badging and f"versionName='{PREFIX}" in badging and MARKER in tree
            and certificate(signer, apk) == expected_certificate)


def installed_paths(serial, package):
    result = subprocess.run(['adb', '-s', serial, 'shell', 'pm', 'path', package], capture_output=True, text=True)
    if result.returncode == 0:
        return result.stdout.splitlines()
    # PackageManager returns 1 without output for an absent package. Device/transport errors must abort.
    if result.returncode == 1 and not result.stdout.strip() and not result.stderr.strip():
        return []
    raise RuntimeError(f'Cannot inspect {package}: {result.stderr.strip() or result.stdout.strip()}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'output/payment-fixtures')
    parser.add_argument('--install', metavar='EMULATOR_SERIAL')
    args = parser.parse_args()
    if args.install:
        try:
            require_emulator(args.install)
        except ValueError as error:
            parser.error(str(error))
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    tools = sdk / 'build-tools/35.0.0'
    android = sdk / 'platforms/android-36/android.jar'
    for path in [android, tools / 'aapt2', tools / 'd8', tools / 'zipalign', tools / 'apksigner']:
        if not path.is_file():
            parser.error(f'Prepare the existing Android SDK tool first: {path}')
    for command in ['javac', 'keytool']:
        if shutil.which(command) is None:
            parser.error(f'JDK tool is required: {command}')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    signing = Path.home() / '.cache/huayoupu-ledger-test-fixtures/signing.p12'
    signing.parent.mkdir(parents=True, exist_ok=True)
    if not signing.exists():
        run(['keytool', '-genkeypair', '-keystore', signing, '-storetype', 'PKCS12', '-storepass', 'synthetic-only',
             '-keypass', 'synthetic-only', '-alias', 'synthetic', '-keyalg', 'RSA', '-keysize', '2048',
             '-validity', '3650', '-dname', 'CN=Huayoupu Synthetic Emulator Fixture'])
    common = ROOT / 'tools/payment-fixtures/PaymentFixtureActivity.java'
    metadata = dict(synthetic=True, sourceSha256=digest(common), builderSha256=digest(Path(__file__)), targetSdk=36, minSdk=30, packages=[])
    apks = []
    for package, components in COMPONENTS.items():
        with tempfile.TemporaryDirectory(prefix='fixture-', dir=output) as temporary:
            work = Path(temporary)
            sources = [common]
            activities = []
            for index, component in enumerate(components):
                namespace, name = component.rsplit('.', 1)
                path = work / (component.replace('.', '/') + '.java')
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f'package {namespace};\npublic final class {name} extends net.ankio.ledger.fixtures.PaymentFixtureActivity {{}}\n')
                sources.append(path)
                launcher = '<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>' if index == 0 else ''
                activities.append(f'<activity android:name="{component}" android:exported="true" android:launchMode="singleTop">{launcher}</activity>')
            manifest = work / 'AndroidManifest.xml'
            manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="101" android:versionName="{PREFIX}1">
<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/>
<application android:label="花有谱合成支付夹具" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:debuggable="true" android:testOnly="true" android:allowBackup="false">
<meta-data android:name="{MARKER}" android:value="v1"/>{''.join(activities)}
</application></manifest>''')
            classes, dex = work / 'classes', work / 'dex'
            classes.mkdir(); dex.mkdir()
            run(['javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', android, '-d', classes, *sources])
            run([tools / 'd8', '--lib', android, '--min-api', '30', '--output', dex, *sorted(classes.rglob('*.class'))])
            unsigned, aligned = work / 'unsigned.apk', work / 'aligned.apk'
            run([tools / 'aapt2', 'link', '-I', android, '--manifest', manifest, '--min-sdk-version', '30', '--target-sdk-version', '36', '-o', unsigned])
            with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.write(dex / 'classes.dex', 'classes.dex')
            run([tools / 'zipalign', '-P', '16', '-f', '4', unsigned, aligned])
            apk = output / f'{package}-synthetic.apk'
            run([tools / 'apksigner', 'sign', '--ks', signing, '--ks-key-alias', 'synthetic', '--ks-pass', 'pass:synthetic-only', '--out', apk, aligned])
            cert = certificate(tools / 'apksigner', apk)
            apks.append((package, apk, cert))
            metadata['packages'].append(dict(package=package, apk=apk.name, sha256=digest(apk), certificateSha256=cert,
                                             versionName=PREFIX + '1', components=components))
    (output / 'build-info.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    if args.install:
        serial = args.install
        # Verify every existing package before installing either fixture. Never uninstall an unknown app.
        for package, apk, cert in apks:
            paths = installed_paths(serial, package)
            if not paths:
                continue
            remote = next((line.removeprefix('package:') for line in paths if line.endswith('/base.apk')), paths[0].removeprefix('package:'))
            with tempfile.TemporaryDirectory(prefix='installed-fixture-', dir=output) as temporary:
                existing = Path(temporary) / 'base.apk'
                run(['adb', '-s', serial, 'pull', remote, existing])
                if not is_signed_fixture(tools / 'aapt2', tools / 'apksigner', existing, package, cert):
                    parser.error(f'Existing {package} is not this signed synthetic fixture; it was left untouched')
        for package, apk, _ in apks:
            run(['adb', '-s', serial, 'install', '-r', '-t', apk])
        (output / f'installed-{serial}.json').write_text(json.dumps(dict(serial=serial, qemu=True, packages=metadata['packages']), indent=2) + '\n')
    print(f'Synthetic fixture APKs: {output}')


if __name__ == '__main__':
    main()
