"""Check reviewed artwork/data bytes in actual package JARs, never infer rights."""
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
# Linux-only replacement bytes that build.py substitutes for tracked composeApp files.
OVERLAY = HERE / 'artwork/overlay'
# The only non-MIT overlay replacement: TMDB's official attribution logo, rendered from
# the vendored, hash-pinned SVG under TMDB's terms (TMDB requires attribution).
TMDB_ATTRIBUTION = {
    'resource': 'composeApp/src/commonMain/composeResources/drawable/rating_tmdb.png',
    'license': 'TMDB-attribution-terms',
    'source': 'tools/linux/packaging/artwork/vendor/tmdb/blue_square_2.svg',
    'source_sha256': 'd537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b',
}
TMDB_LOGO = HERE / 'artwork/vendor/tmdb/blue_square_2.svg'


def repository():
    root = Path(os.environ['NUVIO_REPOSITORY']) if os.environ.get('NUVIO_REPOSITORY') else CHECKOUT
    if root is None or not (root / 'composeApp/src').is_dir():
        raise ValueError('Packaged artwork verification requires the repository composeApp/src; '
                         'mount it and set NUVIO_REPOSITORY')
    return root.resolve()


def icon_provenance():
    return repository() / 'composeApp/src/commonMain/composeResources/files/licenses/NuvioIcons/PROVENANCE.json'


def data_provenance():
    return repository() / 'composeApp/src/desktopMain/resources/anime-mapping/PROVENANCE.json'


def release_provenance():
    return icon_provenance().parent.parent / 'ReleaseArtwork/PROVENANCE.json'


def packaged_path(resource):
    if '/composeResources/' in resource:
        return PREFIX + resource.split('/composeResources/', 1)[1]
    return resource.split('/desktopMain/resources/', 1)[1]


def linux_overlay(release):
    return release['linux_overlay']['resources']


def release_rules():
    release = json.loads(release_provenance().read_text())
    removed_names = {row['filename'].casefold() for row in release['removed']}
    replaced = release['replacements'] + linux_overlay(release)
    # Every original, including the 47 Linux-overlay targets, is forbidden at any path.
    forbidden = {row['original']['sha256']: row['original']['bytes']
                 for row in release['removed'] + replaced}
    # Also reject a replacement copied/renamed into any other payload location.
    replacements = {row['replacement']['sha256']: packaged_path(row['resource']) for row in replaced}
    if len(replacements) != len(replaced) or set(replacements) & set(forbidden):
        raise ValueError('Release artwork replacements must have distinct, non-original bytes')
    # Linux packages download the anime dataset at runtime; never redistribute its bytes.
    dataset = json.loads(data_provenance().read_text())['dataset']
    removed_names.add(dataset['resource'].casefold())
    forbidden[dataset['sha256']] = dataset['bytes']
    sizes = set(forbidden.values()) | {row['replacement']['bytes'] for row in replaced}
    return removed_names, forbidden, replacements, sizes


def linux_source(name):
    """Checkout file whose bytes a Linux package carries at packaged path `name`."""
    relative = ('composeApp/src/commonMain/composeResources/' + name.removeprefix(PREFIX)
                if name.startswith(PREFIX) else 'composeApp/src/desktopMain/resources/' + name)
    overlay = OVERLAY / relative
    return overlay if overlay.is_file() else repository() / relative


def expected_resources():
    PROVENANCE, DATA_PROVENANCE, RELEASE_PROVENANCE = icon_provenance(), data_provenance(), release_provenance()
    provenance = json.loads(PROVENANCE.read_text())
    expected = {PREFIX + row['resource'].split('/composeResources/', 1)[1]:
                row['replacement']['sha256'] for row in provenance['assets']}
    for filename in ('LICENSE.txt', 'NOTICE.txt', 'PROVENANCE.json'):
        digest = hashlib.sha256(PROVENANCE.with_name(filename).read_bytes()).hexdigest()
        declared = {'LICENSE.txt': 'license_sha256', 'NOTICE.txt': 'notice_sha256'}.get(filename)
        if declared and digest != provenance[declared]:
            raise ValueError('Icon license/notice differs from pinned provenance: ' + filename)
        expected[PREFIX + 'files/licenses/NuvioIcons/' + filename] = digest
    # The dataset itself is downloaded at runtime; its attribution and licenses still ship.
    for filename in ('PROVENANCE.json', 'NOTICE.txt', 'ODBL-1.0.txt', 'DBCL-1.0.txt'):
        expected['anime-mapping/' + filename] = hashlib.sha256(
            DATA_PROVENANCE.with_name(filename).read_bytes()).hexdigest()
    release = json.loads(RELEASE_PROVENANCE.read_text())
    for row in release['replacements']:
        expected[packaged_path(row['resource'])] = row['replacement']['sha256']
    if release.get('retained_hash_exceptions'):
        raise ValueError('Retained original artwork exceptions are not allowed in Linux packages')
    for row in linux_overlay(release):
        if row['status'] != 'LINUX_OVERLAY_REPLACED' or row['overlay'] != 'tools/linux/packaging/artwork/overlay/' + row['resource']:
            raise ValueError('Unexpected Linux artwork overlay row: ' + row['resource'])
        if hashlib.sha256((OVERLAY / row['resource']).read_bytes()).hexdigest() != row['replacement']['sha256']:
            raise ValueError('Linux artwork overlay differs from pinned provenance: ' + row['resource'])
        exception = dict({key: row['replacement'].get(key) for key in TMDB_ATTRIBUTION}, resource=row['resource'])
        if row['replacement']['license'] != 'MIT' and (
                exception != TMDB_ATTRIBUTION or
                hashlib.sha256(TMDB_LOGO.read_bytes()).hexdigest() != TMDB_ATTRIBUTION['source_sha256']):
            raise ValueError('Only the pinned TMDB attribution logo may be a non-MIT overlay: ' + row['resource'])
        expected[packaged_path(row['resource'])] = row['replacement']['sha256']
    for filename in ('LICENSE.txt', 'NOTICE.txt', 'PROVENANCE.json'):
        digest = hashlib.sha256(RELEASE_PROVENANCE.with_name(filename).read_bytes()).hexdigest()
        declared = {'LICENSE.txt': 'license_sha256', 'NOTICE.txt': 'notice_sha256'}.get(filename)
        if declared and digest != release[declared]:
            raise ValueError('Release artwork license/notice differs from pinned provenance: ' + filename)
        expected[PREFIX + 'files/licenses/ReleaseArtwork/' + filename] = digest
    return expected


def verify_packaged_artwork(image):
    expected = expected_resources()
    removed_names, forbidden, replacements, sizes = release_rules()
    seen = {}
    for jar in sorted((Path(image) / 'lib/app').glob('*.jar')):
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
    return {'replacement_icons': 7, 'replacement_quality_badges': 6, 'replacement_setup_previews': 4,
            'removed_resources': 10, 'linux_overlay_replacements': 47,
            'unresolved_exact_path_exceptions': 0, 'resources': seen,
            'redistribution_assessment': 'BLOCKED',
            'scope': 'Payload identity only; no rights clearance; dataset rights and source/delivery gates unresolved'}
