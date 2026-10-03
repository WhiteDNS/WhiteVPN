#!/usr/bin/env python3
"""Build the public pinned AmneziaWG core; one isolated Go runtime per connection."""
import hashlib, json, os, pathlib, shutil, subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE=ROOT/'native/amneziawg'
def main():
 env=dict(os.environ)
 env.update(GOTOOLCHAIN='go1.26.8',GOOS='android',CGO_ENABLED='1',GOCACHE=str(ROOT/'build/engines/go-cache'),GOPATH=str(ROOT/'build/engines/go-path'))
 sdk=pathlib.Path(env.get('ANDROID_HOME',env.get('ANDROID_SDK_ROOT','')))
 ndk=pathlib.Path(env.get('ANDROID_NDK_HOME',sdk/'ndk/27.0.12077973'))
 host='windows-x86_64' if os.name=='nt' else 'linux-x86_64'
 tools=ndk/'toolchains/llvm/prebuilt'/host/'bin'
 go=shutil.which('go') or 'C:/Program Files/Go/bin/go.exe'
 env['CGO_CFLAGS']='-I'+str(ndk/'toolchains/llvm/prebuilt'/host/'sysroot/usr/include')
 env['CGO_LDFLAGS']='-Wl,-z,max-page-size=16384,-z,common-page-size=16384,-soname,libamneziawg.so'
 subprocess.run([go,'mod','verify'],cwd=SOURCE,env=env,check=True)
 outputs={}
 for arch,abi,target in [('arm','armeabi-v7a','armv7a-linux-androideabi26'),('arm64','arm64-v8a','aarch64-linux-android26'),('386','x86','i686-linux-android26'),('amd64','x86_64','x86_64-linux-android26')]:
  env.update(GOARCH=arch,GOARM='7',CC=str(tools/('clang.exe' if os.name=='nt' else 'clang'))+' --target='+target)
  output=ROOT/'app/src/main/jniLibs'/abi/'libamneziawg.so'
  output.parent.mkdir(parents=True,exist_ok=True)
  subprocess.run([go,'build','-mod=readonly','-trimpath','-buildvcs=false','-buildmode=c-shared','-ldflags=-s -w','-o',str(output),'.'],cwd=SOURCE,env=env,check=True)
  outputs[abi]=hashlib.sha256(output.read_bytes()).hexdigest()
  output.with_suffix('.h').unlink(missing_ok=True)
 manifest={'androidSource':'fb7575a54e35d9a19cb1f64cdd90bf7075163b55','core':'github.com/amnezia-vpn/amneziawg-go/v3@v3.1.20260814','go':env['GOTOOLCHAIN'],'outputs':outputs,'adapterSha256':{name:hashlib.sha256((SOURCE/name).read_bytes()).hexdigest() for name in ('go.mod','go.sum','main.go','jni.c')}}
 path=ROOT/'build/engines/amneziawg-build.json'; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(json.dumps(manifest,indent=2))
if __name__=='__main__': main()
