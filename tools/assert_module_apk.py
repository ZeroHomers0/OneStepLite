#!/usr/bin/env python3
"""Verify a packaged module APK the way the Xposed loader will read it.

Checks the entry class, the Xposed assets, the licence bundle and, when asked,
the uncompressed ARM64 native library. Accepts either an APK file or a
directory holding exactly one APK, so Gradle can point it at the release
output directory before the signed or unsigned name is known.
"""
import argparse
import sys
import zipfile
from pathlib import Path

ENTRY_CLASS = 'io.github.zerohomers0.onesteplite.HostEntry'
ENTRY_DESCRIPTOR = b'Lio/github/zerohomers0/onesteplite/HostEntry;'
NATIVE_ENTRY = 'libonesteplite_raw.so'
NATIVE_LIBRARY = 'lib/arm64-v8a/libonesteplite_raw.so'
LICENSES = (
    'LICENSE',
    'THIRD_PARTY_NOTICES.md',
    'SOURCE.txt',
    'APACHE-2.0.txt',
    'XposedBridge-NOTICE.txt',
    'libcxx-LICENSE.txt',
    'libcxxabi-LICENSE.txt',
)


def resolve(target):
    path = Path(target)
    if path.is_dir():
        found = sorted(path.glob('*.apk'))
        if len(found) != 1:
            raise SystemExit(f'{path}: expected exactly one APK, found {len(found)}')
        return found[0]
    if not path.is_file():
        raise SystemExit(f'{path}: no such APK')
    return path


def single(archive, name):
    entries = [entry for entry in archive.infolist() if entry.filename == name]
    if len(entries) != 1 or entries[0].file_size == 0:
        raise SystemExit(f'Module APK requires exactly one nonempty {name}')
    return entries[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', help='APK file, or a directory holding one APK')
    parser.add_argument('--require-native', action='store_true')
    options = parser.parse_args()

    apk = resolve(options.target)
    with zipfile.ZipFile(apk) as archive:
        for name in ('AndroidManifest.xml', 'classes.dex', 'assets/xposed_init'):
            single(archive, name)

        entry_class = archive.read('assets/xposed_init').decode('utf-8').strip()
        if entry_class != ENTRY_CLASS:
            raise SystemExit(f'Unexpected Xposed entry: {entry_class}')

        dex = single(archive, 'classes.dex')
        if ENTRY_DESCRIPTOR not in archive.read('classes.dex'):
            raise SystemExit('The Xposed entry descriptor is missing from classes.dex.')

        if options.require_native:
            for name in ('assets/native_init', NATIVE_LIBRARY):
                single(archive, name)
            native_entry = archive.read('assets/native_init').decode('utf-8').strip()
            if native_entry != NATIVE_ENTRY:
                raise SystemExit(f'Unexpected native entry: {native_entry}')
            library = single(archive, NATIVE_LIBRARY)
            if library.compress_size != library.file_size:
                raise SystemExit('Native library must be stored uncompressed for APK loading.')
            header = archive.read(NATIVE_LIBRARY)[:20]
            if len(header) != 20 or header[:5] != b'\x7fELF\x02' or int.from_bytes(header[18:20], 'little') != 183:
                raise SystemExit('Native library must be ELF64 ARM64.')

        for notice in LICENSES:
            single(archive, f'assets/onesteplite/licenses/{notice}')

        print(f'apk: {apk}')
        print(f'entryClass: {entry_class}')
        print(f'dexBytes: {dex.file_size}')
        print('startupPackaging: passed')
        print(f"nativePackaging: {'passed' if options.require_native else 'not requested'}")


if __name__ == '__main__':
    sys.exit(main())
