"""Check reviewed artwork/data bytes in actual package JARs, never infer rights."""
import hashlib
import json
from pathlib import Path
import zipfile

REPO = Path(__file__).resolve().parents[3]
PREFIX = 'composeResources/nuvio.composeapp.generated.resources/'
PROVENANCE = REPO / 'composeApp/src/commonMain/composeResources/files/licenses/NuvioIcons/PROVENANCE.json'
DATA_PROVENANCE = REPO / 'composeApp/src/desktopMain/resources/anime-mapping/PROVENANCE.json'


def expected_resources():
    provenance = json.loads(PROVENANCE.read_text())
    expected = {PREFIX + row['resource'].split('/composeResources/', 1)[1]:
                row['replacement']['sha256'] for row in provenance['assets']}
    for filename in ('LICENSE.txt', 'NOTICE.txt', 'PROVENANCE.json'):
        digest = hashlib.sha256(PROVENANCE.with_name(filename).read_bytes()).hexdigest()
        declared = {'LICENSE.txt': 'license_sha256', 'NOTICE.txt': 'notice_sha256'}.get(filename)
        if declared and digest != provenance[declared]:
            raise ValueError('Icon license/notice differs from pinned provenance: ' + filename)
        expected[PREFIX + 'files/licenses/NuvioIcons/' + filename] = digest
    data = json.loads(DATA_PROVENANCE.read_text())
    expected['anime-list-mini.json'] = data['dataset']['sha256']
    for filename in ('PROVENANCE.json', 'NOTICE.txt', 'ODBL-1.0.txt', 'DBCL-1.0.txt'):
        expected['anime-mapping/' + filename] = hashlib.sha256(
            DATA_PROVENANCE.with_name(filename).read_bytes()).hexdigest()
    return expected


def verify_packaged_artwork(image):
    expected = expected_resources()
    seen = {}
    for jar in sorted((Path(image) / 'lib/app').glob('*.jar')):
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.infolist():
                if entry.filename not in expected:
                    continue
                if entry.filename in seen:
                    raise ValueError('Duplicate reviewed artwork/data: ' + entry.filename)
                digest = hashlib.sha256(archive.read(entry)).hexdigest()
                if digest != expected[entry.filename]:
                    raise ValueError('Reviewed artwork/data hash mismatch: ' + entry.filename)
                seen[entry.filename] = digest
    if set(expected) != set(seen):
        raise ValueError('Missing reviewed artwork/data: ' + ', '.join(sorted(set(expected) - set(seen))))
    return {'replacement_icons': 7, 'resources': seen,
            'redistribution_assessment': 'BLOCKED',
            'scope': 'Payload identity only; other artwork, dataset rights and source/delivery gates unresolved'}
