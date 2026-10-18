"""A small but real native-image build record for the packaging tests (no GraalVM, no app).

`make()` lays out what createLinuxNativeDistributable and linuxNativeCompile leave in a
source snapshot (absolute-path argfile, classpath JARs, metadata dirs, reports, layout),
runs the real native_evidence.stage() on it, and returns the staged image and the
retained evidence directory. The executable is a byte string that embeds the JAR
resources verbatim, as native-image stores them.
"""
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import client_config
import native_evidence

JDK_LIBRARIES = ('libawt.so', 'libawt_xawt.so', 'libfontmanager.so', 'libjava.so', 'libjvm.so')
RELEASE = ('IMPLEMENTOR="GraalVM Community"\nJAVA_RUNTIME_VERSION="25.0.2+10-jvmci-b01"\nJAVA_VERSION="25.0.2"\n'
           'GRAALVM_VERSION="25.0.2"\nSOURCE=".:git:2ea5f0bb564c+ compiler:981ecb6e1be6bf4591a6dd24b48ea053221209f5"\n')
CONFIG = {'tools': {'graalvm': {'url': 'https://example.invalid/graalvm.tar.gz', 'sha256': '0' * 64}}}


def supabase_class(url=None, key=None):
    """A minimal real class file: SupabaseConfig with public static final String URL/ANON_KEY."""
    import struct
    defaults = client_config.public_defaults()
    url, key = url if url is not None else defaults['URL'], key if key is not None else defaults['ANON_KEY']
    utf8 = lambda text: b'\x01' + struct.pack('>H', len(text.encode())) + text.encode()
    pool = [utf8('com/nuvio/app/core/network/SupabaseConfig'), b'\x07' + struct.pack('>H', 1),
            utf8('java/lang/Object'), b'\x07' + struct.pack('>H', 3), utf8('URL'), utf8('ANON_KEY'),
            utf8('Ljava/lang/String;'), utf8('ConstantValue'), utf8(url), b'\x08' + struct.pack('>H', 9),
            utf8(key), b'\x08' + struct.pack('>H', 11)]
    field = lambda name, value: struct.pack('>HHHHHIH', 0x19, name, 7, 1, 8, 2, value)
    return (b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, 52, len(pool) + 1) + b''.join(pool) +
            struct.pack('>HHHH', 0x21, 2, 4, 0) + struct.pack('>H', 2) + field(5, 10) + field(6, 12) +
            struct.pack('>HH', 0, 0))


def jar_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as jar:
        for name, data in entries.items():
            jar.writestr(name, data)
    return stream.getvalue()


def graalvm_home(root):
    home = root / 'graalvm'
    (home / 'lib/svm').mkdir(parents=True)
    (home / 'legal/java.base').mkdir(parents=True)
    for name in ('LICENSE.txt', 'THIRD_PARTY_LICENSE.txt'):
        (home / name).write_text('fixture ' + name + '\n')
    for path in (home / 'LICENSE_NATIVEIMAGE.txt', home / 'lib/svm/LICENSE_NATIVEIMAGE.txt'):
        path.write_text('fixture native-image license\n')
    (home / 'legal/java.base/LICENSE').write_text('fixture java.base license\n')
    (home / 'release').write_text(RELEASE)
    return home


def make(root, jars=None, embedded=None, extra_exe=b'', backend=True, args_extra=(), anime=None):
    """Returns (image, evidence, exe bytes). `jars`: {name: {entry: bytes}}; `embedded`: entry names."""
    root = Path(root)
    source, work = root / 'source', root / 'work'
    jars = jars if jars is not None else {'application.jar': {
        'composeResources/fixture.ttf': b'fixture font bytes', 'META-INF/LICENSE': b'fixture license',
        client_config.CONFIG_CLASS: supabase_class()},
        'library.jar': {'META-INF/NOTICE.txt': b'fixture notice', 'library/Code.class': b'\xca\xfe'}}
    app = source / 'composeApp/build/compose/binaries/main/app/nuvio/lib/app'
    app.mkdir(parents=True)
    for name, entries in jars.items():
        (app / name).write_bytes(jar_bytes(entries))
    for directory in ('composeApp/native-image-linux/agent', 'composeApp/build/native-image/generated'):
        (source / directory).mkdir(parents=True)
        (source / directory / 'reachability-metadata.json').write_text('{}\n')
    anime_dir = source / 'composeApp/src/desktopMain/resources/anime-mapping'
    anime_dir.mkdir(parents=True)
    for name in native_evidence.ANIME_FILES:
        (anime_dir / name).write_bytes((anime or {}).get(name, ('fixture ' + name + '\n').encode()))
    build = source / 'composeApp/build/native-image-linux/work'
    build.mkdir(parents=True)
    classpath = ':'.join(str(app / name) for name in jars)
    args = ['-J-Xmx9g', '-H:ConfigurationFileDirectories=' + ','.join(
                str(source / d) for d in ('composeApp/native-image-linux/agent', 'composeApp/build/native-image/generated')),
            '-H:+GenerateBuildArtifactsFile', '-march=compatibility', '-o', str(build / 'nuvio'),
            '-cp', classpath, '--add-opens=java.desktop/sun.awt=ALL-UNNAMED', *args_extra, 'com.nuvio.app.MainKt']
    (build / 'ni-args.txt').write_text('\n'.join(args) + '\n')
    (build / 'build-artifacts.json').write_text(json.dumps(
        {'executables': ['nuvio'], 'jdk_libraries': list(JDK_LIBRARIES)}))
    names = embedded if embedded is not None else [n for e in jars.values() for n in e if not n.endswith('.class')]
    report = []
    for jar, entries in jars.items():
        for name in entries:
            if name in names:
                report.append({'name': name, 'entries': [{'origin': 'file://' + str(app / jar) + '!/' + name,
                                                          'registration_origin': 'fixture', 'size': len(entries[name])}]})
    report.append({'name': 'java/lang/uniName.dat', 'module': 'java.base',
                   'entries': [{'origin': 'jrt:/java.base/java/lang/uniName.dat', 'size': 4}]})
    (build / 'embedded-resources.json').write_text(json.dumps(report))
    payload = b''.join(entries[n] for entries in jars.values() for n in entries if n in names)
    defaults = client_config.public_defaults()
    exe = (b'fixture executable (not ELF)\x00' + payload + b'\x00' +
           ((defaults['URL'] + '\x00' + defaults['ANON_KEY']).encode() if backend else b'') + extra_exe)
    image = work / 'canonical'
    (image / 'lib/runtime/lib').mkdir(parents=True)
    (image / 'lib/nuvio').write_bytes(exe)
    (image / 'lib/nuvio').chmod(0o755)
    for name in JDK_LIBRARIES:
        (image / 'lib' / name).write_bytes(b'fixture ' + name.encode())
    (image / 'lib/runtime/lib/libjawt.so').write_bytes(b'fixture libjawt')
    (image / 'lib/runtime/lib/fontconfig.properties.src').write_text('fixture\n')
    native_evidence.stage(image, work, source, graalvm_home(root), CONFIG)
    return image, work / 'native-image', exe


def smoke(evidence, image, **changes):
    record = dict(status='PASS', runs=[dict(run=1, first_frame_ms=900, passed=14, failed=0, exit='-15',
                                            failed_checks=False)],
                  executable_sha256=hashlib.sha256((Path(image) / 'lib/nuvio').read_bytes()).hexdigest(),
                  image_identity=native_evidence.image_identity(image), graalvm='25.0.2', vulkan_device='llvmpipe')
    record.update(changes)
    (Path(evidence) / 'smoke.json').write_text(json.dumps(record))
    return record
