"""Check reviewed artwork/data bytes in the exact native-image input JARs (verify.py then
requires them in lib/nuvio or share/licenses/anime-mapping), never infer rights."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import zipfile

HERE = Path(__file__).resolve().parent
# Containers may mount only this directory (/packaging); never walk to a parent
# that isn't there. Reviewed provenance then comes from NUVIO_REPOSITORY.
CHECKOUT = next((parent for parent in HERE.parents if parent / 'tools/linux/packaging' == HERE), None)
PREFIX = 'composeResources/nuvio.composeapp.generated.resources/'


def repository():
    root = Path(os.environ['NUVIO_REPOSITORY']) if os.environ.get('NUVIO_REPOSITORY') else CHECKOUT
    if root is None or not (root / 'composeApp/src').is_dir():
        raise ValueError('Packaged artwork verification requires the repository composeApp/src; '
                         'mount it and set NUVIO_REPOSITORY')
    return root.resolve()


def data_provenance():
    return repository() / 'composeApp/src/desktopMain/resources/anime-mapping/PROVENANCE.json'


def release_provenance():
    return repository() / 'composeApp/src/commonMain/composeResources/files/licenses/ReleaseArtwork/PROVENANCE.json'


def packaged_path(resource):
    if '/composeResources/' in resource:
        return PREFIX + resource.split('/composeResources/', 1)[1]
    return resource.split('/desktopMain/resources/', 1)[1]


def release_rules():
    release = json.loads(release_provenance().read_text())
    replaced = release['replacements']
    # The replaced setup-preview originals are forbidden at any path.
    forbidden = {row['original']['sha256']: row['original']['bytes'] for row in replaced}
    # Also reject a replacement copied/renamed into any other payload location.
    replacements = {row['replacement']['sha256']: packaged_path(row['resource']) for row in replaced}
    if len(replacements) != len(replaced) or set(replacements) & set(forbidden):
        raise ValueError('Release artwork replacements must have distinct, non-original bytes')
    # Linux packages download the anime dataset at runtime; never redistribute its bytes.
    dataset = json.loads(data_provenance().read_text())['dataset']
    removed_names = {dataset['resource'].casefold()}
    forbidden[dataset['sha256']] = dataset['bytes']
    sizes = set(forbidden.values()) | {row['replacement']['bytes'] for row in replaced}
    return removed_names, forbidden, replacements, sizes


def linux_source(name):
    """Checkout file whose bytes a Linux package carries at packaged path `name`."""
    relative = ('composeApp/src/commonMain/composeResources/' + name.removeprefix(PREFIX)
                if name.startswith(PREFIX) else 'composeApp/src/desktopMain/resources/' + name)
    return repository() / relative


def expected_resources():
    DATA_PROVENANCE, RELEASE_PROVENANCE = data_provenance(), release_provenance()
    expected = {}
    # The dataset itself is downloaded at runtime; its attribution and licenses still ship.
    for filename in ('PROVENANCE.json', 'NOTICE.txt', 'ODBL-1.0.txt', 'DBCL-1.0.txt'):
        expected['anime-mapping/' + filename] = hashlib.sha256(
            DATA_PROVENANCE.with_name(filename).read_bytes()).hexdigest()
    release = json.loads(RELEASE_PROVENANCE.read_text())
    for row in release['replacements']:
        expected[packaged_path(row['resource'])] = row['replacement']['sha256']
    for filename in ('LICENSE.txt', 'NOTICE.txt', 'PROVENANCE.json'):
        digest = hashlib.sha256(RELEASE_PROVENANCE.with_name(filename).read_bytes()).hexdigest()
        declared = {'LICENSE.txt': 'license_sha256', 'NOTICE.txt': 'notice_sha256'}.get(filename)
        if declared and digest != release[declared]:
            raise ValueError('Release artwork license/notice differs from pinned provenance: ' + filename)
        expected[PREFIX + 'files/licenses/ReleaseArtwork/' + filename] = digest
    return expected


def verify_packaged_artwork(image, jars=None):
    expected = expected_resources()
    removed_names, forbidden, replacements, sizes = release_rules()
    seen = {}
    for jar in (sorted((Path(image) / 'lib/app').glob('*.jar')) if jars is None else jars):
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.infolist():
                basename = PurePosixPath(entry.filename.replace('\\', '/')).name.casefold()
                if basename in removed_names:
                    raise ValueError('Removed artwork filename: ' + entry.filename)
                digest = None
                if entry.file_size in sizes:
                    digest = hashlib.sha256(archive.read(entry)).hexdigest()
                    if digest in forbidden:
                        raise ValueError('Forbidden historical artwork bytes: ' + entry.filename)
                    if digest in replacements and replacements[digest] != entry.filename:
                        raise ValueError('Renamed/duplicate replacement artwork: ' + entry.filename)
                if entry.filename not in expected:
                    continue
                if entry.filename in seen:
                    raise ValueError('Duplicate reviewed artwork/data: ' + entry.filename)
                digest = digest or hashlib.sha256(archive.read(entry)).hexdigest()
                if digest != expected[entry.filename]:
                    raise ValueError('Reviewed artwork/data hash mismatch: ' + entry.filename)
                seen[entry.filename] = digest
    if set(expected) != set(seen):
        raise ValueError('Missing reviewed artwork/data: ' + ', '.join(sorted(set(expected) - set(seen))))
    return {'replacement_setup_previews': len(replacements), 'resources': seen,
            'upstream_artwork': 'Badges, icons, logos and marks ship upstream bytes unchanged (owner decision 2026-10-17)',
            'redistribution_assessment': 'BLOCKED',
            'scope': 'Payload identity only; no rights clearance; dataset rights and source/delivery gates unresolved'}
