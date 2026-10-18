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


def packaged_path(resource):
    if '/composeResources/' in resource:
        return PREFIX + resource.split('/composeResources/', 1)[1]
    return resource.split('/desktopMain/resources/', 1)[1]


def linux_source(name):
    """Checkout file whose bytes a Linux package carries at packaged path `name`."""
    relative = ('composeApp/src/commonMain/composeResources/' + name.removeprefix(PREFIX)
                if name.startswith(PREFIX) else 'composeApp/src/desktopMain/resources/' + name)
    return repository() / relative


# Linux-only payload rules, one function per resource family so each can be dropped on its
# own (git revert of the commit that removes it brings it back). Each returns a dict:
#   expected     {packaged name: sha256} that must ship exactly once with these bytes
#   forbidden    {sha256: size} whose bytes must not ship under any name
#   removed      {casefolded basename} that must not ship
#   replacements {sha256: (packaged name, size)} pinned bytes allowed only at that name
RULES = []


def rule(function):
    RULES.append(function)
    return function


@rule
def anime_dataset_rules():
    """Linux packages download the anime dataset at runtime; never redistribute its bytes."""
    dataset = json.loads(data_provenance().read_text())['dataset']
    return dict(expected={}, forbidden={dataset['sha256']: dataset['bytes']},
                removed={dataset['resource'].casefold()}, replacements={})


def release_rules():
    removed_names, forbidden, replacements = set(), {}, {}
    for rules in [function() for function in RULES]:
        removed_names |= rules['removed']
        forbidden.update(rules['forbidden'])
        replacements.update(rules['replacements'])
    sizes = set(forbidden.values()) | {size for _, size in replacements.values()}
    return removed_names, forbidden, {digest: name for digest, (name, _) in replacements.items()}, sizes


def expected_resources():
    DATA_PROVENANCE = data_provenance()
    expected = {}
    # Attribution and licenses of the anime dataset always ship.
    for filename in ('PROVENANCE.json', 'NOTICE.txt', 'ODBL-1.0.txt', 'DBCL-1.0.txt'):
        expected['anime-mapping/' + filename] = hashlib.sha256(
            DATA_PROVENANCE.with_name(filename).read_bytes()).hexdigest()
    for function in RULES:
        expected.update(function()['expected'])
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
    return {'replacement_payloads': len(replacements), 'resources': seen,
            'upstream_artwork': 'Badges, icons, logos and marks ship upstream bytes unchanged (owner decision 2026-10-17)',
            'redistribution_assessment': 'BLOCKED',
            'scope': 'Payload identity only; no rights clearance; dataset rights and source/delivery gates unresolved'}
