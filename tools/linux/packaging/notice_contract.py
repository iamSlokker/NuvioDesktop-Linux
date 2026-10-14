"""Ship and check pinned native third-party notices; coverage only, never a rights grant."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil

NOTICES = Path(__file__).resolve().parent / 'notices'
TARGET = PurePosixPath('share/licenses/third-party')


def manifest():
    data = json.loads((NOTICES / 'manifest.json').read_text())
    if hashlib.sha256((NOTICES / 'NOTICE.txt').read_bytes()).hexdigest() != data['notice_sha256']:
        raise ValueError('Native notice summary differs from its pinned manifest')
    return data


def expected():
    files = {'NOTICE.txt': manifest()['notice_sha256'],
             'manifest.json': hashlib.sha256((NOTICES / 'manifest.json').read_bytes()).hexdigest()}
    for component, record in manifest()['components'].items():
        for name, digest in record['files'].items():
            files[component + '/' + name] = digest
    return files


def stage_notices(image):
    destination = Path(image) / TARGET
    for name, digest in expected().items():
        source = NOTICES / name
        if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise ValueError('Native notice differs from its pinned manifest: ' + name)
        (destination / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination / name)


def verify_packaged_notices(image):
    root = Path(image) / TARGET
    for name, digest in expected().items():
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError('Missing native third-party notice: ' + str(TARGET / name))
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Native third-party notice hash mismatch: ' + str(TARGET / name))
    return {'components': len(manifest()['components']), 'files': len(expected()),
            'path': str(TARGET), 'scope': 'Notice coverage only; not legal clearance or source completeness'}
