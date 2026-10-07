#!/usr/bin/env python3
"""Build Android Mosh from checksum-pinned sources; Linux x86_64 host, NDK r29.
Run on local storage: python3 scripts/build_mosh.py [--abi x86_64].
No private material enters the build. All outputs live under build/mosh.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/mosh'
NDK_VERSION = '29.0.14206865'
ABIS = {'arm64-v8a': ('aarch64-linux-android', 'android-arm64'),
        'armeabi-v7a': ('armv7a-linux-androideabi', 'android-arm'),
        'x86_64': ('x86_64-linux-android', 'android-x86_64')}
JOBS = str(min(8, os.cpu_count() or 2))


def run(args, cwd, env=None):
    print(' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def source(name, spec):
    archive = WORK / (name + '.tar.gz')
    if not archive.exists():
        run(['curl', '-fL', '--retry', '3', spec['url'], '-o', archive], ROOT)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != spec['sha256']:
        raise SystemExit(f'Checksum mismatch: {name}')
    target = WORK / 'sources' / name
    if not target.exists():
        target.mkdir(parents=True)
        with tarfile.open(archive) as tar:
            # Pinned archives, stripped top-level directory; reject traversal and escaping links.
            members = tar.getmembers()
            for m in members:
                parts = Path(m.name).parts[1:]
                if not parts:
                    continue
                m.name = str(Path(*parts))
                tar.extract(m, target, filter='data')
    return target


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--abi', action='append', choices=ABIS)
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ['ANDROID_HOME'])
    ndk = sdk / 'ndk' / NDK_VERSION
    tc = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    if not tc.is_dir():
        raise SystemExit(f'Install ndk;{NDK_VERSION} with sdkmanager first')
    specs = json.loads((ROOT / 'native/mosh/sources.json').read_text())
    sources = {name: source(name, spec) for name, spec in specs.items()}
    # Use the matching compiler on the build host (never an Android executable).
    host = WORK / 'host-protobuf'
    protoc = host / 'protoc'
    if not protoc.exists():
        run(['cmake', '-S', sources['protobuf'] / 'cmake', '-B', host, '-G', 'Ninja',
             '-DCMAKE_BUILD_TYPE=Release', '-Dprotobuf_BUILD_TESTS=OFF'], ROOT)
        run(['cmake', '--build', host, '--target', 'protoc', '-j', JOBS], ROOT)
    for abi in args.abi or ABIS:
        triple, openssl_target = ABIS[abi]
        host_triple = 'arm-linux-androideabi' if abi == 'armeabi-v7a' else triple
        folder = WORK / abi
        folder.mkdir(exist_ok=True)
        prefix = folder / 'prefix'
        out = WORK / 'jniLibs' / abi
        out.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env.update(CC=str(tc / (triple + '26-clang')), CXX=str(tc / (triple + '26-clang++')),
                   AR=str(tc / 'llvm-ar'), RANLIB=str(tc / 'llvm-ranlib'), STRIP=str(tc / 'llvm-strip'),
                   CFLAGS='-O2 -fPIC', CXXFLAGS='-O2 -fPIC', LDFLAGS='-Wl,-z,max-page-size=16384',
                   ANDROID_NDK_ROOT=str(ndk), PATH=str(tc) + ':' + os.environ['PATH'],
                   PKG_CONFIG_LIBDIR=str(prefix / 'lib/pkgconfig'), PKG_CONFIG_PATH=str(prefix / 'lib/pkgconfig'))
        # OpenSSL and ncurses libraries are static; APK contains no dependent shared libraries.
        if not (prefix / 'lib/libcrypto.a').exists():
            d = folder / 'openssl'; d.mkdir(exist_ok=True)
            run([sources['openssl'] / 'Configure', openssl_target, '-D__ANDROID_API__=26',
                 'no-shared', 'no-tests', 'no-apps', '--libdir=lib', '--prefix=' + str(prefix)], d, env)
            run(['make', '-j' + JOBS], d, env)
            run(['make', 'install_sw'], d, env)
        if not (prefix / 'lib/libncursesw.a').exists():
            d = folder / 'ncurses'; d.mkdir(exist_ok=True)
            run([sources['ncurses'] / 'configure', '--host=' + host_triple, '--prefix=' + str(prefix),
                 '--with-normal', '--without-shared', '--without-debug', '--enable-widec',
                 '--without-ada', '--without-cxx', '--without-cxx-binding', '--without-tests', '--without-progs'], d, env)
            run(['make', '-j' + JOBS], d, env)
            run(['make', 'install.libs', 'install.includes'], d, env)
        if not (prefix / 'lib/libprotobuf.a').exists():
            d = folder / 'protobuf'
            run(['cmake', '-S', sources['protobuf'] / 'cmake', '-B', d, '-G', 'Ninja',
                 '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
                 '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
                 '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_INSTALL_PREFIX=' + str(prefix),
                 '-Dprotobuf_BUILD_TESTS=OFF', '-Dprotobuf_BUILD_PROTOC_BINARIES=OFF',
                 '-Dprotobuf_WITH_ZLIB=OFF'], ROOT, env)
            run(['cmake', '--build', d, '-j', JOBS], ROOT, env)
            run(['cmake', '--install', d], ROOT, env)
        d = folder / 'mosh'; d.mkdir(exist_ok=True)
        env.update(CPPFLAGS=f'-I{prefix}/include -I{prefix}/include/ncursesw',
                   LDFLAGS=f'-L{prefix}/lib -static-libstdc++ -Wl,-z,max-page-size=16384',
                   PROTOC=str(protoc), protobuf_CFLAGS=f'-I{prefix}/include',
                   protobuf_LIBS=f'-L{prefix}/lib -lprotobuf',
                   OpenSSL_CFLAGS=f'-I{prefix}/include', OpenSSL_LIBS=f'-L{prefix}/lib -lcrypto',
                   TINFO_CFLAGS=f'-I{prefix}/include/ncursesw', TINFO_LIBS=f'-L{prefix}/lib -lncursesw')
        if not (d / 'Makefile').exists():
            run([sources['mosh'] / 'configure', '--host=' + host_triple, '--disable-server',
                 '--with-crypto-library=openssl', '--without-utempter'], d, env)
        run(['make', '-j' + JOBS, 'LIBS=-llog -lz'], d, env)
        shutil.copy2(d / 'src/frontend/mosh-client', out / 'libmosh-client.so')
        run([env['CC'], '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
             '-Wl,-z,max-page-size=16384', ROOT / 'native/mosh/pty.c', '-o', out / 'libshelldeck-pty.so'], ROOT, env)
        for binary in out.glob('*.so'):
            run([env['STRIP'], binary], ROOT)
    assets = WORK / 'assets/terminfo'; assets.mkdir(parents=True, exist_ok=True)
    run(['tic', '-x', '-e', 'xterm-256color', '-o', assets, sources['ncurses'] / 'misc/terminfo.src'], ROOT)
    notices = WORK / 'assets/mosh-licenses'; notices.mkdir(parents=True, exist_ok=True)
    for name, filename in [('mosh', 'COPYING'), ('protobuf', 'LICENSE'), ('ncurses', 'COPYING'), ('openssl', 'LICENSE.txt')]:
        shutil.copy2(sources[name] / filename, notices / (name + '.txt'))
    # Refuse stale native outputs after local source edits, even outside CI cache invalidation.
    fingerprint = hashlib.sha256()
    for path in ['scripts/build_mosh.py', 'native/mosh/sources.json', 'native/mosh/pty.c']:
        fingerprint.update((ROOT / path).read_bytes())
    (WORK / 'fingerprint').write_text(fingerprint.hexdigest())

if __name__ == '__main__':
    main()
