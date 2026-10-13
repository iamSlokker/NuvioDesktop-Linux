"""Verify the actual packaged CPU inference payload, without loading app classes.

Native hashes are from com.microsoft.onnxruntime:onnxruntime:1.31.0 on Maven
Central (JAR SHA256 d3f903af31fe8f01300adb6b2b67297aa5bee7895909afc32e76ef29a3921149).
Model and notice provenance is retained in backdrop-framing/NOTICE.txt.
"""
import hashlib
import os
import pathlib
import re
import subprocess
import tempfile
import zipfile

NATIVE_PREFIX = 'ai/onnxruntime/native/linux-x64/'
NATIVES = {
    NATIVE_PREFIX + 'libonnxruntime.so': 'b92844ae8e1df7e8e14f63c1d3731e3cd2175bd445dd0c1a1fe5aeff44e044c0',
    NATIVE_PREFIX + 'libonnxruntime4j_jni.so': 'd04ea50a193234981e11039bc5e0c9c7602b42aa7f17d6d469b817931e2d7029',
}
RESOURCES = {
    'u2netp.onnx': '309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8',
    'yunet.onnx': '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4',
    'NOTICE.txt': 'dc91726bbd4100c57026473b255495a46f784587abd70778459de7941ad9f36c',
    'LICENSE-U2NET.txt': 'c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4',
    'LICENSE-YUNET.txt': 'c83b8120c50ccbd4c4f96edf53141bdd566ebb8f8e9227e415326aa1b1aba958',
    'LICENSE-ONNXRUNTIME.txt': '2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c',
}


def verify_packaged_onnx(image, abi_check):
    expected = dict(NATIVES, **{'backdrop-framing/' + name: sha for name, sha in RESOURCES.items()})
    found, payloads = {}, {}
    for path in sorted((image / 'lib/app').glob('*.jar')):
        with zipfile.ZipFile(path) as jar:
            for entry in jar.infolist():
                name = entry.filename
                if entry.is_dir():
                    continue
                if name.startswith('ai/onnxruntime/native/') and name not in NATIVES:
                    raise ValueError('Unexpected ONNX platform/provider library: ' + name)
                if name not in expected:
                    continue
                data = jar.read(entry)
                if name in found or hashlib.sha256(data).hexdigest() != expected[name]:
                    raise ValueError('Duplicate/tampered ONNX payload: ' + name)
                found[name] = dict(jar=path.name, sha256=expected[name])
                if name in NATIVES:
                    payloads[name] = data
    if found.keys() != expected.keys():
        raise ValueError('Missing ONNX payload: ' + ', '.join(sorted(expected.keys() - found.keys())))

    with tempfile.TemporaryDirectory(prefix='nuvio-onnx-audit-') as tmp:
        root = pathlib.Path(tmp)
        for name, data in payloads.items():
            (root / pathlib.PurePosixPath(name).name).write_bytes(data)
        # ORT's Java loader System.load()s the core before JNI. Model that
        # already-loaded SONAME for ldd, which cannot express Java load order.
        # This alias is an audit fixture, never a rewritten packaged library.
        (root / 'libonnxruntime.so.1').symlink_to('libonnxruntime.so')
        env = dict(os.environ, LD_LIBRARY_PATH=str(root))
        env.pop('LD_PRELOAD', None)
        for name in NATIVES:
            path = root / pathlib.PurePosixPath(name).name
            abi_check(path, name)
            dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
            needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
            allowed = {'libdl.so.2', 'librt.so.1', 'libpthread.so.0', 'libstdc++.so.6',
                       'libm.so.6', 'libgcc_s.so.1', 'libc.so.6', 'ld-linux-x86-64.so.2', 'libonnxruntime.so.1'}
            if not needed or set(needed) - allowed:
                raise ValueError('Unexpected ONNX CPU dependency: ' + name)
            # Exact pinned upstream JNI bytes contain an inert build RUNPATH;
            # the core is preloaded by SONAME. Do not patch vendor binaries or
            # relax the image-wide relocatable ELF policy for other libraries.
            expected_path = '/build/Release' if path.name.endswith('4j_jni.so') else '$ORIGIN'
            if re.findall(r'\((?:RUNPATH|RPATH)\).*?\[(.*?)\]', dynamic) != [expected_path]:
                raise ValueError('Unexpected pinned ONNX search path: ' + name)
            linked = subprocess.check_output(['ldd', str(path)], text=True, env=env, stderr=subprocess.STDOUT)
            if 'not found' in linked:
                raise ValueError('Unresolved ONNX CPU dependency: ' + name + '\n' + linked)
            if path.name.endswith('4j_jni.so') and f'libonnxruntime.so.1 => {root}/libonnxruntime.so.1 ' not in linked:
                raise ValueError('JNI did not resolve the packaged ONNX core')
    return dict(version='1.31.0', provider='CPU', files=found)
