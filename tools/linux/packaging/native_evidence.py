"""GraalVM native-image inputs, notices and smoke record for the Linux packages.

Packages ship lib/nuvio (the native executable) and the JDK libraries
native-image emits, never application JARs or a JVM. The JAR contracts
(client_config, onnx/artwork) therefore run on the exact native-image
input JARs, which `record()` retains next to the build as evidence:

  <work>/native-image/inputs/*.jar        the classpath native-image compiled
  <work>/native-image/inputs.json         path-free manifest, also shipped as
                                          share/nuvio-native-image/inputs.json
  <work>/native-image/build-artifacts.json, embedded-resources.json
                                          native-image's own reports (evidence only)
  <work>/native-image/smoke.json          native_smoke.py's passing record

`verify()` binds a package to that evidence: the shipped manifest equals the
retained one, every retained JAR matches its recorded SHA-256 and the shipped
executable matches the one native-image produced. Resources are embedded
verbatim (uncompressed), so `contained()` proves a payload's exact bytes are in
lib/nuvio. Nothing here loads a class, JNI library or the application.
"""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import zipfile

PACKAGED_MANIFEST = PurePosixPath('share/nuvio-native-image/inputs.json')
EXECUTABLE = 'lib/nuvio'
GRAALVM_NOTICES = PurePosixPath('share/licenses/GraalVM')
JAR_NOTICES = PurePosixPath('share/licenses/jar-notices')
ANIME_NOTICES = PurePosixPath('share/licenses/anime-mapping')
ANIME_FILES = ('PROVENANCE.json', 'NOTICE.txt', 'ODBL-1.0.txt', 'DBCL-1.0.txt')
# Same rule as compliance/inventory.jar_details: every notice-like JAR entry.
NOTICE_ENTRY = re.compile(r'(^|/)([^/]*(?:license|notice|copyright)[^/]*)$', re.I)
# GraalVM CE's own license files (lib/svm/LICENSE_NATIVEIMAGE.txt equals the top-level copy).
GRAALVM_FILES = ('LICENSE.txt', 'LICENSE_NATIVEIMAGE.txt', 'THIRD_PARTY_LICENSE.txt', 'release')
RELEASE_KEYS = ('IMPLEMENTOR', 'JAVA_VERSION', 'JAVA_RUNTIME_VERSION', 'GRAALVM_VERSION', 'SOURCE')
# A JVM image must never come back: no JARs, launcher library, class archive or HotSpot.
FORBIDDEN_JVM = ('lib/runtime/lib/server', 'lib/runtime/bin', 'lib/runtime/lib/modules', 'lib/runtime/release',
                 'lib/runtime/lib/libjli.so', 'lib/libapplauncher.so', 'lib/native-qa')


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def image_identity(root):
    """Digest of every path and its bytes (links by target); modes are left to the format audits."""
    root = Path(root)
    records = {}
    for path in sorted(root.rglob('*')):
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            records[name] = 'link:' + os.readlink(path)
        elif path.is_file():
            records[name] = sha256(path)
    return hashlib.sha256(json.dumps(records, sort_keys=True).encode()).hexdigest()


def parse_argfile(text):
    """Lines of the Gradle task's argfile (linuxArgLine: quoted only when needed)."""
    args = []
    for line in text.splitlines():
        if not line:
            continue
        if line.startswith('"'):
            if not line.endswith('"') or len(line) < 2:
                raise ValueError('Malformed native-image argument line: ' + line)
            line = re.sub(r'\\(.)', r'\1', line[1:-1])
        args.append(line)
    return args


def relative_to(path, root, label):
    path = Path(path)
    if not path.is_absolute() or not path.resolve().is_relative_to(Path(root).resolve()):
        raise ValueError(label + ' outside the source snapshot: ' + str(path))
    return path.resolve().relative_to(Path(root).resolve()).as_posix()


def portable_arguments(args, source):
    """Path-free native-image arguments, the metadata directories and the classpath."""
    portable, directories, classpath = [], [], []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg in ('-cp', '-o'):
            if index + 1 >= len(args):
                raise ValueError('Missing value for ' + arg)
            value = args[index + 1]
            if arg == '-cp':
                classpath = [Path(item) for item in value.split(':') if item]
                portable += ['-cp', ':'.join(path.name for path in classpath)]
            else:
                portable += ['-o', Path(value).name]
            index += 2
            continue
        if arg.startswith('-H:ConfigurationFileDirectories='):
            directories = [relative_to(item, source, 'Metadata directory')
                           for item in arg.split('=', 1)[1].split(',') if item]
            arg = '-H:ConfigurationFileDirectories=' + ','.join(directories)
        if arg.startswith('/') or '/source/' in arg or re.search(r'(?<![A-Za-z0-9_.])/(?:var/)?home/', arg):
            raise ValueError('Build-machine path in native-image argument: ' + arg)
        portable.append(arg)
        index += 1
    if not classpath or not directories:
        raise ValueError('native-image arguments lack a classpath or metadata directories')
    return portable, directories, classpath


def release_fields(home):
    fields = {}
    for line in (Path(home) / 'release').read_text().splitlines():
        key, sep, value = line.partition('=')
        if sep and key in RELEASE_KEYS:
            fields[key] = value.strip().strip('"')
    if fields.get('IMPLEMENTOR') != 'GraalVM Community' or not fields.get('GRAALVM_VERSION'):
        raise ValueError('The native image must be built with GraalVM Community Edition')
    return fields


def notice_dir(jar):
    """share/licenses/jar-notices/<JAR name without .jar>/: no directory may look like a JAR."""
    if not jar.endswith('.jar') or '/' in jar or jar.startswith('.'):
        raise ValueError('Unexpected input JAR name: ' + jar)
    return jar[:-len('.jar')]


def jar_notices(jars):
    """{jar name: [(entry, bytes)]} for every notice-like entry, as the JVM packages shipped them."""
    found = {}
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            names = [entry.filename for entry in archive.infolist()]
            if len(names) != len(set(names)):
                raise ValueError('Duplicate JAR member in ' + jar.name)
            for entry in archive.infolist():
                relative = PurePosixPath(entry.filename)
                if entry.is_dir() or entry.filename.endswith('.class') or not NOTICE_ENTRY.search(entry.filename):
                    continue
                if relative.is_absolute() or '..' in relative.parts or '\\' in entry.filename:
                    raise ValueError('Unsafe notice entry: ' + jar.name + ':' + entry.filename)
                found.setdefault(jar.name, []).append((entry.filename, archive.read(entry)))
    return found


def record(work, source, graalvm, dist, config):
    """Retain native-image's inputs/reports (in the builder) and return the manifest to ship."""
    work, source, dist = Path(work), Path(source), Path(dist)
    evidence = work / 'native-image'
    shutil.rmtree(evidence, ignore_errors=True)
    (evidence / 'inputs').mkdir(parents=True)
    build = source / 'composeApp/build/native-image-linux/work'
    args, directories, classpath = portable_arguments(
        parse_argfile((build / 'ni-args.txt').read_text()), source)
    if '-R:MissingRegistrationReportingMode=Warn' in args or (dist / 'lib/native-qa').exists():
        raise ValueError('A native-image QA build must never be packaged')
    jars = []
    for jar in classpath:
        if jar.suffix != '.jar' or not jar.is_file() or jar.is_symlink():
            raise ValueError('Unexpected native-image classpath entry: ' + jar.name)
        target = evidence / 'inputs' / jar.name
        if target.exists():
            raise ValueError('Duplicate native-image classpath name: ' + jar.name)
        shutil.copy2(jar, target)
        jars.append(dict(name=jar.name, sha256=sha256(target), bytes=target.stat().st_size))
    metadata = []
    for directory in directories:
        for path in sorted((source / directory).rglob('*')):
            if path.is_file():
                metadata.append(dict(path=path.relative_to(source).as_posix(), sha256=sha256(path)))
    for report in ('build-artifacts.json', 'embedded-resources.json'):
        if not (build / report).is_file():
            raise ValueError('native-image did not write ' + report)
        shutil.copy2(build / report, evidence / report)
    emitted = sorted(p.name for p in dist.joinpath('lib').iterdir() if p.is_file() and p.suffix == '.so')
    reported = sorted(PurePosixPath(name).name for name in
                      json.loads((build / 'build-artifacts.json').read_text()).get('jdk_libraries', []))
    if emitted != reported:
        raise ValueError(f'Emitted JDK libraries differ from native-image report: {emitted} != {reported}')
    libraries = {('lib/' + name): sha256(dist / 'lib' / name) for name in emitted}
    for path in sorted((dist / 'lib/runtime/lib').iterdir()):
        libraries['lib/runtime/lib/' + path.name] = sha256(path)
    home = Path(graalvm)
    notices = {name: sha256(home / name) for name in GRAALVM_FILES}
    if sha256(home / 'lib/svm/LICENSE_NATIVEIMAGE.txt') != notices['LICENSE_NATIVEIMAGE.txt']:
        raise ValueError('GraalVM native-image license copies differ')
    for path in sorted((home / 'legal').rglob('*')):
        if path.is_file():
            notices['legal/' + path.relative_to(home / 'legal').as_posix()] = sha256(path)
    manifest = dict(
        schema_version=1,
        scope='Exact inputs of the shipped native executable. The JARs are retained as build evidence '
              '(Nuvio-native-image-inputs-*.tar), not installed; their notices are in share/licenses/jar-notices.',
        graalvm=dict(release_fields(home), archive_url=config['tools']['graalvm']['url'],
                     archive_sha256=config['tools']['graalvm']['sha256'], notices=notices),
        arguments=args, classpath=jars, metadata=metadata,
        executable=dict(path=EXECUTABLE, sha256=sha256(dist / EXECUTABLE)),
        jdk_files=libraries)
    data = json.dumps(manifest, indent=2, sort_keys=True) + '\n'
    (evidence / 'inputs.json').write_text(data)
    return data


def stage(image, work, source, graalvm, config):
    """Manifest and notices in the canonical image, from the retained evidence."""
    image, work, source, home = Path(image), Path(work), Path(source), Path(graalvm)
    data = record(work, source, home, image, config)
    target = image / PACKAGED_MANIFEST
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(data)
    notices = image / GRAALVM_NOTICES
    notices.mkdir(parents=True)
    for name in GRAALVM_FILES:
        shutil.copyfile(home / name, notices / name)
    shutil.copytree(home / 'legal', notices / 'legal', symlinks=False)
    index = {}
    jars = [work / 'native-image/inputs' / row['name'] for row in json.loads(data)['classpath']]
    for jar, entries in jar_notices(jars).items():
        index[jar] = {}
        for name, payload in entries:
            path = image / JAR_NOTICES / notice_dir(jar) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
            index[jar][name] = hashlib.sha256(payload).hexdigest()
    (image / JAR_NOTICES / 'index.json').write_text(json.dumps(index, indent=2, sort_keys=True) + '\n')
    # The anime dataset is embedded; its attribution and licenses ship as files
    # (native-image embeds only resources the app reads, and the app never reads these).
    anime = image / ANIME_NOTICES
    anime.mkdir(parents=True)
    for name in ANIME_FILES:
        shutil.copyfile(source / 'composeApp/src/desktopMain/resources/anime-mapping' / name, anime / name)


class NativeImage:
    def __init__(self, manifest, jars, executable):
        self.manifest, self.jars, self.executable = manifest, jars, executable

    def contained(self, payload):
        return bool(payload) and payload in self.executable


def verify(root, evidence):
    """Bind a package root to the retained native-image evidence; returns a NativeImage."""
    root, evidence = Path(root), Path(evidence)
    shipped = root / PACKAGED_MANIFEST
    if shipped.is_symlink() or not shipped.is_file():
        raise ValueError('Missing native-image inputs manifest: ' + str(PACKAGED_MANIFEST))
    if shipped.read_bytes() != (evidence / 'inputs.json').read_bytes():
        raise ValueError('Packaged native-image manifest differs from the retained build evidence')
    manifest = json.loads(shipped.read_text())
    if manifest.get('schema_version') != 1 or manifest['graalvm'].get('IMPLEMENTOR') != 'GraalVM Community':
        raise ValueError('Unsupported native-image manifest')
    names = [row['name'] for row in manifest['classpath']]
    retained = sorted(p.name for p in (evidence / 'inputs').iterdir())
    if sorted(names) != retained or len(set(names)) != len(names):
        raise ValueError('Retained native-image JARs differ from the manifest classpath')
    jars = []
    for row in manifest['classpath']:
        path = evidence / 'inputs' / row['name']
        if path.is_symlink() or not path.is_file() or sha256(path) != row['sha256']:
            raise ValueError('Retained native-image JAR differs from its manifest: ' + row['name'])
        jars.append(path)
    exe = root / EXECUTABLE
    if exe.is_symlink() or not exe.is_file() or not os.access(exe, os.X_OK):
        raise ValueError('Missing/non-executable native executable: ' + EXECUTABLE)
    executable = exe.read_bytes()
    if hashlib.sha256(executable).hexdigest() != manifest['executable']['sha256']:
        raise ValueError('Packaged native executable differs from the native-image output')
    for name, digest in manifest['jdk_files'].items():
        path = root / name
        if path.is_symlink() or not path.is_file() or sha256(path) != digest:
            raise ValueError('Missing/different native-image JDK file: ' + name)
    for name in FORBIDDEN_JVM:
        if os.path.lexists(root / name):
            raise ValueError('JVM runtime/launcher or QA marker in a native package: ' + name)
    bundled_jars = sorted(p.relative_to(root).as_posix() for p in root.rglob('*.jar') if not p.is_dir())
    if bundled_jars:
        raise ValueError('Application/dependency JAR in a native package: ' + ', '.join(bundled_jars[:5]))
    for name, digest in manifest['graalvm']['notices'].items():
        path = root / GRAALVM_NOTICES / name
        if path.is_symlink() or not path.is_file() or sha256(path) != digest:
            raise ValueError('Missing/different GraalVM notice: ' + str(GRAALVM_NOTICES / name))
    verify_jar_notices(root, jars)
    return NativeImage(manifest, jars, executable)


def verify_jar_notices(root, jars):
    expected = {jar: {name: hashlib.sha256(payload).hexdigest() for name, payload in entries}
                for jar, entries in jar_notices(jars).items()}
    directory = Path(root) / JAR_NOTICES
    if json.loads((directory / 'index.json').read_text()) != expected:
        raise ValueError('Packaged JAR notice index differs from the native-image input JARs')
    actual = {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file() or p.is_symlink()}
    if actual != {'index.json'} | {notice_dir(jar) + '/' + name for jar, rows in expected.items() for name in rows}:
        raise ValueError('Unexpected/missing packaged JAR notice files')
    for jar, rows in expected.items():
        for name, digest in rows.items():
            path = directory / notice_dir(jar) / name
            if path.is_symlink() or sha256(path) != digest:
                raise ValueError('Packaged JAR notice differs: ' + jar + '/' + name)
    return dict(path=str(JAR_NOTICES), jars=len(expected), files=sum(len(rows) for rows in expected.values()))


def embedded_resources(evidence, native):
    """native-image's own report: every embedded JAR resource exists in a recorded input JAR, same size."""
    report = json.loads((Path(evidence) / 'embedded-resources.json').read_text())
    sizes = {}
    for jar in native.jars:
        with zipfile.ZipFile(jar) as archive:
            sizes[jar.name] = {entry.filename: entry.file_size for entry in archive.infolist() if not entry.is_dir()}
    names, other = set(), dict(jdk_module=0, injected_service_registrations=0)
    for resource in report:
        if resource.get('is_directory') or resource.get('is_missing'):
            continue
        for entry in resource['entries']:
            origin = entry.get('origin', '')
            if resource.get('module') or origin.startswith('jrt:'):
                other['jdk_module'] += 1  # JDK module resources of the pinned GraalVM.
                continue
            if origin == 'INJECTED':
                # Service registrations native-image generates (RuntimeResourceAccess); nothing else.
                if not resource['name'].startswith('META-INF/services/') or '/' in resource['name'][18:]:
                    raise ValueError('Unexpected injected resource in the native image: ' + resource['name'])
                other['injected_service_registrations'] += 1
                continue
            owners = [name for name in sizes if re.search(r'(^|[/:])' + re.escape(name) + r'(!|$)', origin)]
            if len(owners) != 1:
                raise ValueError('Embedded resource from an unrecorded origin: ' + resource['name'] + ' ' + origin)
            if sizes[owners[0]].get(resource['name']) != entry.get('size'):
                raise ValueError('Embedded resource differs from its input JAR entry: ' + resource['name'])
            names.add(resource['name'])
    return names, other


def check_smoke(evidence, root, identity=None):
    """The passing native smoke record must describe this exact executable (and image, when given)."""
    path = Path(evidence) / 'smoke.json'
    if not path.is_file():
        raise ValueError('No passing native smoke record; the smoke gate must pass before packaging')
    record = json.loads(path.read_text())
    if record.get('status') != 'PASS' or not record.get('runs') or any(
            run.get('failed') != 0 or not run.get('passed') or run.get('first_frame_ms') is None
            for run in record['runs']):
        raise ValueError('Native smoke record is not a pass')
    if record['executable_sha256'] != sha256(Path(root) / EXECUTABLE):
        raise ValueError('Native smoke record describes a different executable')
    if identity is not None and record['image_identity'] != identity:
        raise ValueError('Native smoke record describes a different package payload')
    return dict(status=record['status'], runs=len(record['runs']), image_identity=record['image_identity'])


def evidence_files(work):
    """Files the evidence archive carries (relative to work), in a stable order."""
    work = Path(work)
    names = ['native-image/inputs.json', 'native-image/build-artifacts.json',
             'native-image/embedded-resources.json', 'native-image/smoke.json']
    names += sorted('native-image/inputs/' + p.name for p in (work / 'native-image/inputs').iterdir())
    names += sorted(p.relative_to(work).as_posix() for p in (work / 'native-smoke').glob('run-*/console.log'))
    return names


def archive(work, output, epoch):
    """Deterministic, uncompressed tar of the native-image evidence for the release artifacts."""
    work = Path(work)
    with tarfile.open(output, 'w', format=tarfile.PAX_FORMAT) as tar:
        for name in evidence_files(work):
            info = tar.gettarinfo(str(work / name), arcname=name)
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            info.mtime = epoch
            info.mode = 0o644
            with (work / name).open('rb') as stream:
                tar.addfile(info, stream)


def check_archive(work, path):
    """The published evidence archive carries exactly the retained evidence bytes."""
    work = Path(work)
    expected = {name: sha256(work / name) for name in evidence_files(work)}
    actual = {}
    with tarfile.open(path) as tar:
        for member in tar.getmembers():
            if not member.isfile() or member.name in actual:
                raise ValueError('Unexpected member in native-image evidence archive: ' + member.name)
            digest = hashlib.sha256()
            stream = tar.extractfile(member)
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
            actual[member.name] = digest.hexdigest()
    if actual != expected:
        raise ValueError('Native-image evidence archive differs from the retained evidence')
    return dict(name=Path(path).name, files=len(expected))
