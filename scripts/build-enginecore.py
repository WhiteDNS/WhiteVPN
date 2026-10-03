#!/usr/bin/env python3
"""Build only Psiphon, zeddns and MasterDNS from the reviewed partner sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PINS = json.loads((ROOT / 'native/engines/sources.json').read_text())
WORK = ROOT / 'build/engines'

def run(args, cwd=None, env=None):
    print('> ' + ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)

def checkout(name, git, env):
    spec = PINS['sources'][name]
    dest = WORK / 'sources' / name
    if not (dest / '.git').exists():
        dest.mkdir(parents=True, exist_ok=True)
        run([git, 'init', dest], env=env)
        run([git, '-C', dest, 'remote', 'add', 'origin', spec['url']], env=env)
        run([git, '-C', dest, 'fetch', '--depth=1', 'origin', spec['commit']], env=env)
        run([git, '-C', dest, 'checkout', '--detach', spec['commit']], env=env)
    revision = subprocess.check_output([git, '-C', str(dest), 'rev-parse', 'HEAD'], env=env, text=True).strip()
    if revision != spec['commit']:
        raise RuntimeError(f'{name}: revision differs from reviewed manifest')
    changes = subprocess.check_output([git, '-C', str(dest), 'status', '--porcelain'], env=env, text=True)
    if changes:
        raise RuntimeError(f'{name}: source checkout must be clean')
    return dest

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sources-only', action='store_true')
    parser.add_argument('--targets', default='android/arm,android/arm64,android/amd64')
    args = parser.parse_args()
    env = dict(os.environ)
    env['GOTOOLCHAIN'] = PINS['go']
    env.setdefault('GOCACHE', str(WORK / 'go-cache'))
    env.setdefault('GOPATH', str(WORK / 'go-path'))
    env['GIT_TERMINAL_PROMPT'] = '0'
    git = shutil.which('git') or r'C:/Program Files/Git/cmd/git.exe'
    go = shutil.which('go') or r'C:/Program Files/Go/bin/go.exe'
    sources = {name: checkout(name, git, env) for name in ('androidlib', 'psiphon', 'masterdns', 'vaydns', 'qpack')}
    if args.sources_only:
        return
    sdk = Path(env.get('ANDROID_HOME', env.get('ANDROID_SDK_ROOT', '')))
    ndk = Path(env.get('ANDROID_NDK_HOME', ''))
    if not (ndk / 'source.properties').is_file():
        choices = sorted((sdk / 'ndk').glob('*'), key=lambda p: tuple(map(int, p.name.split('.'))))
        if not choices:
            raise RuntimeError('Install NDK 27+ and set ANDROID_HOME')
        ndk = choices[-1]
    revision = (ndk / 'source.properties').read_text()
    if not any(f'Pkg.Revision = {version}.' in revision for version in range(27, 40)):
        raise RuntimeError('NDK 27+ required; every output is checked for 16 KB alignment')
    env['ANDROID_NDK_HOME'] = str(ndk)
    java = Path(env.get('JAVA_HOME', ''))
    suffix = '.exe' if os.name == 'nt' else ''
    if not (java / 'bin' / ('javac' + suffix)).exists():
        raise RuntimeError('Set JAVA_HOME to JDK 17 or 21')
    android_jar = sdk / 'platforms/android-36/android.jar'
    if not android_jar.is_file():
        raise RuntimeError('Install the pinned Android SDK platform: platforms;android-36')
    module = WORK / 'module'
    module.mkdir(parents=True, exist_ok=True)
    tools = WORK / 'tools'
    tools.mkdir(parents=True, exist_ok=True)
    env['GOBIN'] = str(tools)
    env['PATH'] = os.pathsep.join((str(tools), str(java / 'bin'), str(Path(go).parent), env.get('PATH', '')))
    env['CGO_LDFLAGS'] = '-Wl,-z,max-page-size=16384,-z,common-page-size=16384'
    # Keep the reviewed checkout immutable. Numeric DoT servers must validate
    # their certificate IP SAN instead of silently disabling verification.
    zeddns = WORK / 'zeddns-verified-tls'
    shutil.copytree(sources['androidlib'] / 'zeddns', zeddns, dirs_exist_ok=True, copy_function=shutil.copyfile)
    transport = zeddns / 'transport.go'
    old = 'cfg.ServerName = ""\n\t\tcfg.InsecureSkipVerify = true'
    original = transport.read_text()
    if original.count(old) != 1:
        raise RuntimeError('Pinned zeddns DoT validation patch no longer matches')
    transport.write_text(original.replace(old, 'cfg.ServerName = host'))
    shutil.copyfile(ROOT / 'native/engines/zeddns-dot-validation_test.go', zeddns / 'whitevpn_dot_validation_test.go')
    tls_patch_hash = hashlib.sha256(transport.read_bytes()).hexdigest()
    replaces = {
        'github.com/Psiphon-Labs/psiphon-tunnel-core': sources['psiphon'],
        'github.com/pion/dtls/v2': sources['psiphon'] / 'replace/dtls',
        'github.com/quic-go/qpack': sources['qpack'],
        'zeddns': zeddns,
        'masterdnsvpn-go': sources['masterdns'],
        'github.com/net2share/vaydns': sources['vaydns'],
    }
    text = 'module whitevpn.enginecore\n\ngo 1.26.0\n\nrequire (\n'
    text += 'github.com/Psiphon-Labs/psiphon-tunnel-core v1.0.11-0.20250319154633-ceb78316d06e\nzeddns v0.0.0\nmasterdnsvpn-go v0.0.0\n)\n'
    text += '\n'.join(f'replace {name} => "{path.as_posix()}"' for name, path in replaces.items()) + '\n'
    (module / 'go.mod').write_text(text)
    (module / 'engines.go').write_text('package enginecore\nimport (\n_ "github.com/Psiphon-Labs/psiphon-tunnel-core/MobileLibrary/psi"\n_ "zeddns"\n_ "masterdnsvpn-go/mobile"\n)\n')
    run([go, 'install', 'golang.org/x/mobile/cmd/gomobile@' + PINS['gomobile']], module, env)
    run([go, 'install', 'golang.org/x/mobile/cmd/gobind@' + PINS['gomobile']], module, env)
    # Pinned gomobile converts an unversioned local replacement into a version-specific
    # replacement, then omits its require directive. Its generated module can select
    # upstream code instead of the reviewed fork. Preserve the parent's local replacements.
    mobile_info = json.loads(subprocess.check_output([go, 'mod', 'download', '-json',
        'golang.org/x/mobile@' + PINS['gomobile']], cwd=module, env=env, text=True))
    tool_source = WORK / 'gomobile-source'
    shutil.copytree(mobile_info['Dir'], tool_source, dirs_exist_ok=True, copy_function=shutil.copyfile)
    bind_source = tool_source / 'cmd/gomobile/bind.go'
    tool_text = bind_source.read_text()
    original = 'f.AddReplace(mod.Path, mod.Version, p, v)'
    if tool_text.count(original) != 1:
        raise RuntimeError('Pinned gomobile local-replacement workaround no longer matches')
    bind_source.chmod(0o644)
    bind_source.write_text(tool_text.replace(original, 'f.AddReplace(mod.Path, "", p, v)'))
    run([go, 'build', '-o', tools / ('gomobile.exe' if os.name == 'nt' else 'gomobile'), './cmd/gomobile'], tool_source, env)
    (WORK / 'gomobile-local-replacement-workaround.json').write_text(json.dumps({
        'module': PINS['gomobile'], 'moduleSum': mobile_info.get('Sum'),
        'change': 'Preserve unversioned local replacements in generated modules',
        'patchedBindSha256': hashlib.sha256(bind_source.read_bytes()).hexdigest()}, indent=2))
    run([go, 'get', '-tool', 'golang.org/x/mobile/cmd/gobind@' + PINS['gomobile']], module, env)
    run([go, 'mod', 'tidy'], module, env)
    run([go, 'test', 'zeddns', '-run', '^TestWhiteVpnDoTRejectsUntrustedIPAddress$', '-count=1'], module, env)
    # Go's dependency list is inspected to prove unrelated partner engines are absent.
    deps = subprocess.check_output([go, 'list', '-deps', './...'], cwd=module, env=env, text=True)
    if any(fragment in deps.lower() for fragment in ('xray-core', 'sing-box', 'androidlibxraylite')):
        raise RuntimeError('Unexpected unrelated engine dependency')
    (WORK / 'enginecore-dependencies.txt').write_text(deps)
    mobile = tools / ('gomobile' + suffix)
    run([mobile, 'init'], module, env)
    raw = WORK / 'enginecore-raw.aar'
    packages = ['github.com/Psiphon-Labs/psiphon-tunnel-core/MobileLibrary/psi', 'zeddns', 'masterdnsvpn-go/mobile']
    run([mobile, 'bind', '-trimpath', '-androidapi', '26', '-target=' + args.targets,
         '-ldflags=-checklinkname=0 -s -w -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384',
         '-o', raw] + packages, module, env)
    with tempfile.TemporaryDirectory(dir=WORK) as temp:
        unpacked = Path(temp)
        with zipfile.ZipFile(raw) as archive:
            archive.extractall(unpacked)
        classes = unpacked / 'classes.jar'
        run([java / 'bin' / ('javac' + suffix), '-d', unpacked, '-bootclasspath', android_jar,
             '-source', '8', '-target', '8', '-classpath', classes,
             sources['psiphon'] / 'MobileLibrary/Android/PsiphonTunnel/PsiphonTunnel.java'], env=env)
        psi_classes = list((unpacked / 'ca/psiphon').glob('*.class'))
        run([java / 'bin' / ('jar' + suffix), 'uf', classes] + [str(p.relative_to(unpacked)) for p in psi_classes], unpacked, env)
        (unpacked / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ca.psiphon"><uses-sdk android:minSdkVersion="26"/></manifest>')
        (unpacked / 'proguard.txt').write_text('-keep class go.** { *; }\n-keep class psi.** { *; }\n-keep class ca.psiphon.** { *; }\n-keep class zeddns.** { *; }\n-keep class masterdns.** { *; }\n')
        out = ROOT / 'app/libs/enginecore.aar'
        out.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as archive:
            for file in unpacked.rglob('*'):
                if file.is_file() and not str(file.relative_to(unpacked)).startswith('ca/'):
                    archive.write(file, file.relative_to(unpacked))
        manifest = {'zedsecure': PINS['zedsecure'], 'sources': PINS['sources'], 'targets': args.targets,
                    'sha256': hashlib.sha256(out.read_bytes()).hexdigest()}
        (WORK / 'enginecore-build.json').write_text(json.dumps(manifest, indent=2))
        shutil.copyfile(module / 'go.sum', WORK / 'enginecore-go.sum')
    print(out, flush=True)

if __name__ == '__main__':
    main()
