#!/usr/bin/env python3
"""Build/audit AppImage using the engine's builder, without rebuilding the app."""
import argparse
import json
import os
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from webkit.build import recipe_identity, valid_cache
from appimage_runtime import prepare as prepare_runtime


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packaging-work-dir', type=pathlib.Path, required=True)
    parser.add_argument('--engine-generation', type=pathlib.Path, required=True)
    args = parser.parse_args()
    work, engine = args.packaging_work_dir.resolve(), args.engine_generation.resolve()
    release = json.loads((work / 'canonical/release-info.json').read_text())
    base = release['builder_image_id']
    if not valid_cache(engine, recipe_identity(base)):
        raise ValueError('No completed engine candidate for current recipe/base image')
    record = json.loads((engine / 'build-complete.json').read_text())
    # Source-built type2 runtime (Podman, pinned inputs) before the offline
    # assembly container; NUVIO_APPIMAGE_RUNTIME_DIR may supply a verified build.
    prepare_runtime(work / 'appimage-runtime', work / 'inputs/appimage-runtime-sources',
                    prebuilt=os.environ.get('NUVIO_APPIMAGE_RUNTIME_DIR'))
    command = ['podman', 'run', '--rm', '--network=none', '--userns=keep-id', '--cap-drop=all',
               '--security-opt', 'label=disable', '--security-opt', 'no-new-privileges',
               '--env', 'PYTHONDONTWRITEBYTECODE=1', '--env', 'PYTHONPATH=/packaging',
               '--env', 'SOURCE_DATE_EPOCH=' + str(release['source_date_epoch']),
               '--env', 'LC_ALL=C', '--env', 'TZ=UTC',
               # Artwork gate reads reviewed provenance from the packaged source snapshot.
               '--env', 'NUVIO_REPOSITORY=/output/source',
               '--mount', f'type=bind,src={work},dst=/output',
               '--mount', f'type=bind,src={engine},dst=/engine,ro',
               '--mount', f'type=bind,src={HERE.parent},dst=/packaging,ro', record['builder_image_id'],
               'python3', '-c', 'import pathlib; from formats import build_appimage; '
               'from verify import artifacts; from webkit.stage import archive_source; '
               'build_appimage(pathlib.Path("/output"), pathlib.Path("/engine")); '
               'archive_source(pathlib.Path("/output"), pathlib.Path("/engine")); '
               'artifacts(pathlib.Path("/output"), "appimage")']
    subprocess.run(command, check=True)


if __name__ == '__main__':
    main()
