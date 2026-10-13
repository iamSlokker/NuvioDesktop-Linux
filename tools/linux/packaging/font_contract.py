"""Verify the actual application JAR font payload, without loading the application."""
import hashlib
import json
from pathlib import Path
import zipfile


MANIFEST = Path(__file__).parent / 'fonts/inter-manifest.json'
PREFIX = 'composeResources/nuvio.composeapp.generated.resources/'


def verify_packaged_fonts(image):
    manifest = json.loads(MANIFEST.read_text())
    records = manifest['files'] + [manifest['license_file'], manifest['notice_file']]
    expected = {PREFIX + item['path'].split('/composeResources/', 1)[1]: item['sha256']
                for item in records}
    removed_names = {item['filename'] for item in manifest['removed_fonts']}
    removed_hashes = {item['sha256'] for item in manifest['removed_fonts']}
    seen = {}
    for jar in sorted((Path(image) / 'lib/app').glob('*.jar')):
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                name = entry.filename
                if Path(name).name in removed_names:
                    raise ValueError('Removed unlicensed font in package: ' + name)
                # A renamed old font must not evade the payload check.
                if name in expected or name.lower().endswith(('.ttf', '.otf')):
                    digest = hashlib.sha256(archive.read(entry)).hexdigest()
                    if digest in removed_hashes:
                        raise ValueError('Removed unlicensed font bytes in package: ' + name)
                    if name in expected:
                        if name in seen:
                            raise ValueError('Duplicate bundled font/notice: ' + name)
                        if digest != expected[name]:
                            raise ValueError('Bundled font/notice hash mismatch: ' + name)
                        seen[name] = digest
    missing = sorted(set(expected) - set(seen))
    if missing:
        raise ValueError('Missing bundled font/notice: ' + ', '.join(missing))
    return dict(component=manifest['component'], version=manifest['version'],
                license=manifest['license'], resources=seen)
