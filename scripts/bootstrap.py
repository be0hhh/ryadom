"""Local, checksum-verified Linux x86_64 build tools; no system installation."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)


def fetch(url, filename, digest=None, algorithm='sha256'):
    path = TOOLS / filename
    if not path.exists() or (digest and hashlib.new(algorithm, path.read_bytes()).hexdigest() != digest):
        subprocess.run(['curl', '-fL', '--retry', '2', '--output', str(path), url], check=True)
    if digest and hashlib.new(algorithm, path.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Checksum mismatch: ' + filename)
    return path


def unzip(path, target):
    with zipfile.ZipFile(path) as archive:
        archive.extractall(target)
        for item in archive.infolist():
            mode = item.external_attr >> 16
            if mode:
                (target / item.filename).chmod(mode)


if sys.platform != 'linux' or os.uname().machine != 'x86_64':
    sys.exit('Этот bootstrap рассчитан на Linux/WSL x86_64. Для других ОС используйте Android Studio.')

jdk = fetch(
    'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/'
    'OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz',
    'jdk.tar.gz', '3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e')
if not (TOOLS / 'jdk/bin/java').exists():
    stage = TOOLS / 'jdk-stage'
    stage.mkdir(exist_ok=True)
    with tarfile.open(jdk) as archive:
        archive.extractall(stage, filter='data')
    shutil.move(str(next(stage.iterdir())), TOOLS / 'jdk')

gradle = fetch('https://downloads.gradle.org/distributions/gradle-8.13-bin.zip', 'gradle.zip',
               '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78')
if not (TOOLS / 'gradle-8.13/bin/gradle').exists():
    unzip(gradle, TOOLS)

sdk = TOOLS / 'android-sdk'
sdk.mkdir(exist_ok=True)
commandline = fetch('https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip',
                    'android-commandline.zip', '5fdcc763663eefb86a5b8879697aa6088b041e70', 'sha1')
if not (sdk / 'cmdline-tools/latest/bin/sdkmanager').exists():
    stage = TOOLS / 'cmdline-stage'
    stage.mkdir(exist_ok=True)
    unzip(commandline, stage)
    (sdk / 'cmdline-tools').mkdir(exist_ok=True)
    shutil.move(str(stage / 'cmdline-tools'), sdk / 'cmdline-tools/latest')

repository = fetch('https://dl.google.com/android/repository/repository2-3.xml', 'android-repository.xml')
license_element = next(e for e in ET.parse(repository).getroot().findall('license')
                       if e.attrib['id'] == 'android-sdk-license')
license_text = license_element.text.strip()
license_file = TOOLS / 'android-sdk-license.txt'
license_file.write_text(license_text)
license_hash = hashlib.sha1(license_text.encode()).hexdigest()
accepted = sdk / 'licenses/android-sdk-license'
if not accepted.exists() or license_hash not in accepted.read_text():
    print('Условия Android SDK сохранены в', license_file)
    if '--accept-sdk-license' not in sys.argv:
        if not sys.stdin.isatty() or input('Принимаете эти условия? [yes/нет] ').strip().lower() != 'yes':
            sys.exit('Лицензия не принята; после прочтения повторите bootstrap с --accept-sdk-license.')
    accepted.parent.mkdir(exist_ok=True)
    with accepted.open('a') as output:
        output.write(license_hash + '\n')

for archive_name, destination, digest in [
    ('platform-36_r02.zip', 'platforms/android-36', '2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576'),
    ('build-tools_r35_linux.zip', 'build-tools/35.0.0', '2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc'),
    ('platform-tools_r37.0.1-linux.zip', 'platform-tools', '477254aa5f903c15cf51001717bdf347fb6b53e0'),
]:
    target = sdk / destination
    if target.exists():
        continue
    archive = fetch('https://dl.google.com/android/repository/' + archive_name, archive_name, digest, 'sha1')
    stage = TOOLS / ('stage-' + archive_name)
    stage.mkdir(exist_ok=True)
    unzip(archive, stage)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(next(stage.iterdir())), target)

(ROOT / 'local.properties').write_text('sdk.dir=' + str(sdk) + '\n')
print('Инструменты готовы. Команда сборки: ./gradlew :app:assembleDebug')
