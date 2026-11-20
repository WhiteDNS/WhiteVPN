#!/usr/bin/env python3
"""Rebuild the partner-selected Tor transports with Android PIE and 16 KB alignment."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

spec = importlib.util.spec_from_file_location('enginebuild', Path(__file__).with_name('build-enginecore.py'))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)
ROOT, WORK, PINS = build.ROOT, build.WORK, build.PINS

def main():
    env = dict(os.environ)
    env.update({'GOTOOLCHAIN': PINS['go'], 'GIT_TERMINAL_PROMPT': '0'})
    env.setdefault('GOCACHE', str(WORK / 'go-cache'))
    env.setdefault('GOPATH', str(WORK / 'go-path'))
    git = shutil.which('git') or 'C:/Program Files/Git/cmd/git.exe'
    go = shutil.which('go') or 'C:/Program Files/Go/bin/go.exe'
    source = {name: build.checkout(name, git, env) for name in ('lyrebird', 'conjure')}
    # Download a fixed Snowflake module through Go's checksum database.
    download = json.loads(subprocess.check_output([go, 'mod', 'download', '-json',
        'gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2@' + PINS['snowflake']], env=env, text=True))
    source['snowflake'] = Path(download['Dir'])
    sdk = Path(env.get('ANDROID_HOME', env.get('ANDROID_SDK_ROOT', '')))
    ndk = Path(env.get('ANDROID_NDK_HOME', ''))
    if not (ndk / 'source.properties').exists():
        ndk = sorted((sdk / 'ndk').glob('*'), key=lambda p: tuple(map(int, p.name.split('.'))))[-1]
    platform = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    binary = ndk / 'toolchains/llvm/prebuilt' / platform / 'bin'
    env['PATH'] = str(binary) + os.pathsep + env.get('PATH', '')
    env['CGO_ENABLED'] = '1'
    env['GOOS'] = 'android'
    env['CGO_LDFLAGS'] = '-Wl,-z,max-page-size=16384,-z,common-page-size=16384'
    builds = (('lyrebird', './cmd/lyrebird', 'libobfs4proxy.so'), ('conjure', './client', 'libconjure.so'), ('snowflake', './client', 'libsnowflake.so'))
    for arch, abi, target in (('arm', 'armeabi-v7a', 'armv7a-linux-androideabi26'), ('arm64', 'arm64-v8a', 'aarch64-linux-android26'), ('amd64', 'x86_64', 'x86_64-linux-android26')):
        env['GOARCH'] = arch
        env['GOARM'] = '7'
        env['CC'] = str(binary / ('clang.exe' if os.name == 'nt' else 'clang')) + ' --target=' + target
        out = ROOT / 'app/src/main/jniLibs' / abi
        out.mkdir(parents=True, exist_ok=True)
        for name, package, library in builds:
            flags = '-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384'
            if name == 'lyrebird': flags += ' -X main.lyrebirdVersion=0.6.1'
            args = [go, 'build', '-trimpath', '-buildmode=pie', '-ldflags=' + flags]
            if name == 'conjure': args += ['-tags=protoreflect']
            build.run(args + ['-o', out / library, package], source[name], env)
            (out / library).chmod(0o755)
    (WORK / 'tor-transports-build.json').write_text(json.dumps({'pins': PINS, 'snowflakeSum': download.get('Sum')}, indent=2))

if __name__ == '__main__': main()
