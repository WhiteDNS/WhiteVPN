#!/usr/bin/env python3
"""Fail closed on missing requested engines, unresolved ELF dependencies or bad alignment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = ('armeabi-v7a', 'arm64-v8a', 'x86_64')
SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so'}
ENGINE_LIBS = {'core': ('libgojni.so',), 'tor': ('libtor.so', 'libobfs4proxy.so', 'libsnowflake.so', 'libconjure.so'), 'amneziawg': ('libamneziawg.so',)}
MACHINES = {'armeabi-v7a': 40, 'arm64-v8a': 183, 'x86': 3, 'x86_64': 62}

def elf_check(path, abi):
    data = path.read_bytes()
    if data[:4] != b'\x7fELF' or data[5] != 1:
        raise RuntimeError(f'{path}: not a little-endian ELF binary')
    if struct.unpack_from('<H', data, 18)[0] != MACHINES[abi]:
        raise RuntimeError(f'{path}: wrong ABI')
    wide = data[4] == 2
    offset = struct.unpack_from('<Q' if wide else '<I', data, 32 if wide else 28)[0]
    size, count = struct.unpack_from('<HH', data, 54 if wide else 42)
    # Android 16 KB validation applies to arm64-v8a/x86_64; 32-bit NDK runtimes remain 4 KB.
    # https://developer.android.com/guide/practices/page-sizes
    required_alignment = 16384 if abi in ('arm64-v8a', 'x86_64') else 4096
    loads = []
    for i in range(count):
        p = offset + i * size
        if struct.unpack_from('<I', data, p)[0] != 1:
            continue
        alignment = struct.unpack_from('<Q' if wide else '<I', data, p + (48 if wide else 28))[0]
        if alignment < required_alignment:
            raise RuntimeError(f'{path}: PT_LOAD alignment is {alignment}, requires {required_alignment}')
        loads.append(alignment)
    if not loads:
        raise RuntimeError(f'{path}: no load segments')
    return hashlib.sha256(data).hexdigest()

def readelf_tool():
    explicit = os.environ.get('READELF')
    if explicit:
        return explicit
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    ndk = Path(os.environ.get('ANDROID_NDK_HOME', ''))
    candidates = list(ndk.glob('toolchains/llvm/prebuilt/*/bin/llvm-readelf*'))
    candidates += list(sdk.glob('ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf*'))
    return str(candidates[-1]) if candidates else shutil.which('readelf')

def inspect(base, requested):
    tool = readelf_tool()
    if not tool:
        raise RuntimeError('Set READELF or ANDROID_NDK_HOME to inspect dependency loading')
    hashes = {}
    for engine in requested:
        supported = tuple(MACHINES) if engine == 'amneziawg' else ABIS
        for abi in supported:
            for name in ENGINE_LIBS[engine]:
                path = base / abi / name
                if not path.is_file():
                    raise RuntimeError(f'Missing {engine} artifact: {abi}/{name}')
    for abi in MACHINES:
        files = list((base / abi).glob('*.so'))
        for path in files:
            hashes[f'{abi}/{path.name}'] = elf_check(path, abi)
            dynamic = subprocess.check_output([tool, '-d', str(path)], text=True)
            for dependency in re.findall(r'Shared library: \[(.+?)\]', dynamic):
                if dependency not in SYSTEM and not (base / abi / dependency).is_file():
                    raise RuntimeError(f'{path}: dependency {dependency} is not packaged')
            if path.name == 'libamneziawg.so':
                symbols = subprocess.check_output([tool, '--dyn-syms', '-W', str(path)], text=True)
                if not all(symbol in symbols for symbol in ('Java_org_amnezia_awg_GoBackend_awgTurnOn', 'Java_org_amnezia_awg_GoBackend_awgTurnOff', 'Java_org_amnezia_awg_GoBackend_awgGetConfig', 'JNI_OnLoad')):
                    raise RuntimeError('AmneziaWG library has no required Android JNI bindings')
            if path.name in ENGINE_LIBS['tor']:
                header = subprocess.check_output([tool, '-h', str(path)], text=True)
                if 'Entry point address:               0x0' in header:
                    raise RuntimeError(f'{path}: expected a runnable executable')
                if os.name != 'nt' and not os.access(path, os.X_OK):
                    raise RuntimeError(f'{path}: executable permission missing')
    return hashes

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--require', nargs='+', choices=tuple(ENGINE_LIBS), default=[])
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--checksums', type=Path)
    parser.add_argument('--write-manifest', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as temp:
        base = ROOT / 'app/src/main/jniLibs'
        if args.apk:
            base = Path(temp)
            with zipfile.ZipFile(args.apk) as archive:
                for entry in archive.namelist():
                    if entry.startswith('lib/') and entry.endswith('.so'):
                        target = base / Path(entry).relative_to('lib')
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(archive.read(entry)); target.chmod(0o755)
                if not any(entry.startswith('assets/tor/') for entry in archive.namelist()) and 'tor' in args.require:
                    raise RuntimeError('Tor bridge assets missing from APK')
        elif (ROOT / 'app/libs/enginecore.aar').exists():
            # Inspect AAR libraries together with separate native dependencies.
            collected = Path(temp)
            for file in base.glob('*/*.so'):
                target = collected / file.parent.name / file.name
                target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(file, target)
            with zipfile.ZipFile(ROOT / 'app/libs/enginecore.aar') as archive:
                for entry in archive.namelist():
                    if entry.startswith('jni/') and entry.endswith('.so'):
                        target = collected / Path(entry).relative_to('jni')
                        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(archive.read(entry))
                with zipfile.ZipFile(__import__('io').BytesIO(archive.read('classes.jar'))) as classes:
                    for required in ('ca/psiphon/PsiphonTunnel.class', 'psi/Psi.class', 'zeddns/Zeddns.class', 'masterdns/Masterdns.class'):
                        if required not in classes.namelist():
                            raise RuntimeError('Missing Java binding: ' + required)
                    if any(entry.startswith(('libv2ray/', 'libbox/')) for entry in classes.namelist()):
                        raise RuntimeError('Unrelated engines were bundled')
            base = collected
        hashes = inspect(base, args.require)
    if args.checksums:
        expected = json.loads(args.checksums.read_text())['sha256']
        if any(hashes.get(key) != value for key, value in expected.items()):
            raise RuntimeError('Artifact checksums differ from build provenance')
    if args.write_manifest:
        args.write_manifest.parent.mkdir(parents=True, exist_ok=True)
        args.write_manifest.write_text(json.dumps({'sha256': hashes}, indent=2))
    print(json.dumps({'verified': hashes}, indent=2))

if __name__ == '__main__':
    main()
