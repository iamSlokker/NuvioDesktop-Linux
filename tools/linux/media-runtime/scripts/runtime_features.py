#!/usr/bin/env python3
"""Query compiled capability and validate the permanent playback evidence."""
import json
import math
import os
import pathlib
import re
import subprocess
import sys

SOFTWARE = ['h264', 'hevc', 'vp9', 'libdav1d', 'aac', 'ac3', 'eac3', 'pcm_s16le']
# The SDR x11vk path stays mandatory; waylandvk is the experimental HDR path.
GPU_CONTEXTS = ['x11vk', 'waylandvk']
# Interface names generated from protocol XML into libmpv. Color management
# requires wayland-protocols >= 1.41 when mpv is configured.
WAYLAND_INTERFACES = ['xdg_wm_base', 'wp_color_manager_v1',
                      'wp_color_management_surface_feedback_v1']
LIBMPV = 'lib/libmpv.so.2.5.0'
# In-process libmpv option parsing only: no mpv_initialize, VO or display.
LIBMPV_CONTEXT_PROBE = '''
import ctypes, json, sys
lib = ctypes.CDLL(sys.argv[1])
lib.mpv_create.restype = ctypes.c_void_p
lib.mpv_set_option_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
lib.mpv_destroy.argtypes = [ctypes.c_void_p]
accepted = []
for name in sys.argv[2:]:
    handle = lib.mpv_create()
    if not handle:
        raise SystemExit('mpv_create failed')
    if lib.mpv_set_option_string(handle, b'gpu-context', name.encode()) == 0:
        accepted.append(name)
    lib.mpv_destroy(handle)
print(json.dumps(accepted))
'''
PLAYBACK_VECTORS = json.loads((pathlib.Path(__file__).resolve().parents[1] /
                              'tests/vectors.json').read_text())


def hwdec_candidates(output):
    """mpv 0.41: post-whitelist, before copy filtering/device creation.

    Keep codec-qualified names and log order. Renderer messages are unrelated.
    'Trying hardware decoding via' occurs too late to observe failed devices.
    """
    return re.findall(r'^\[vd\] Looking at hwdec ([a-z0-9_-]+)\.\.\.\s*$', output, re.M)


def selected_hwdec(output):
    """Final decoding outcome, not candidate consideration or device creation."""
    # mpv's console info-level hardware message omits the module prefix;
    # verbose software messages (and prefixed diagnostics) retain [vd].
    matches = re.findall(r'^(?:\[vd\] )?Using (?:hardware decoding \(([a-z0-9_-]+)\)|'
                         r'(software) decoding)\.\s*$', output, re.M)
    if not matches:
        raise ValueError('Missing actual decoding outcome')
    hardware, _ = matches[-1]
    return hardware if hardware else 'no'


def validate_hwdec_policy(mode, candidates, backend):
    if mode not in ('auto', 'auto-safe', 'auto-copy', 'auto-copy-safe'):
        return  # Explicit Vulkan is intentional opt-in.
    # Normalize only the optional codec prefix, not arbitrary renderer text.
    if any(re.fullmatch(r'(?:[a-z0-9_]+-)?vulkan(?:-copy)?', name) for name in candidates):
        raise ValueError('Safe automatic selection considered Vulkan')
    if backend in ('vulkan', 'vulkan-copy'):
        raise ValueError('Safe automatic selection chose Vulkan')
    # A direct candidate can be logged before mpv's copy filter; selecting one
    # is different, and would violate auto-copy semantics.
    if mode in ('auto-copy', 'auto-copy-safe') and backend != 'no' and not backend.endswith('-copy'):
        raise ValueError('Safe auto-copy selected a non-copy backend')


def validate_playback_report(report):
    """Require successful child evidence before accepting its PASS summary.

    Requirements come from the recipe's permanent vectors, never the report.
    Additional informational fields/entries are allowed; required evidence has
    strict JSON types (in particular, bool is not an integer frame/sample count).
    This validates evidence consistency, not the authenticity of rewritten data.
    """
    def require(condition, field):
        if not condition:
            raise ValueError('Invalid playback report: ' + field)

    def object_at(parent, key, field):
        value = parent.get(key)
        require(isinstance(value, dict), field + ' must be an object')
        return value

    def equal(parent, key, expected, field):
        value = parent.get(key)
        require(type(value) is type(expected) and value == expected,
                field + '.' + key + ' must be ' + repr(expected))

    def count(parent, key, minimum, field):
        value = parent.get(key)
        require(type(value) is int and value >= minimum,
                field + '.' + key + ' must be an integer >= ' + str(minimum))

    def software_hwdec(child, field):
        candidates = child.get('hwdec_candidates')
        require(isinstance(candidates, list) and all(isinstance(x, str) and
                re.fullmatch(r'[a-z0-9_-]+', x) for x in candidates),
                field + '.hwdec_candidates must be an array of hwdec names')
        equal(child, 'backend', 'no', field)
        try:
            validate_hwdec_policy(child['hwdec'], candidates, child['backend'])
        except ValueError as exc:
            require(False, field + ': ' + str(exc))

    require(isinstance(report, dict), 'root must be an object')
    software = object_at(report, 'software', 'software')
    videos = dict(PLAYBACK_VECTORS['vectors'])
    videos['high10_auto_fallback'] = videos['h264-high10']
    for name, vector in videos.items():
        field = 'software.' + name
        child = object_at(software, name, field)
        equal(child, 'result', 'PASS', field)
        for key in ('ffmpeg_decoded_frames', 'mpv_output_frames'):
            count(child, key, vector['expected_frames'], field)
        stream = object_at(child, 'stream', field + '.stream')
        for key, expected in vector['stream'].items():
            equal(stream, key, expected, field + '.stream')
        equal(child, 'hwdec', 'auto' if name == 'high10_auto_fallback' else 'no', field)
        equal(child, 'decoding', 'software', field)
        equal(child, 'reached_eof', True, field)
        software_hwdec(child, field)

    auto = object_at(report, 'auto_fallback', 'auto_fallback')
    for name, vector in PLAYBACK_VECTORS['vectors'].items():
        field = 'auto_fallback.' + name
        child = object_at(auto, name, field)
        for key, value in (('result', 'PASS'), ('hwdec', 'auto'),
                           ('decoding', 'software'), ('reached_eof', True)):
            equal(child, key, value, field)
        for key in ('ffmpeg_decoded_frames', 'mpv_output_frames'):
            count(child, key, vector['expected_frames'], field)
        stream = object_at(child, 'stream', field + '.stream')
        for key, value in vector['stream'].items():
            equal(stream, key, value, field + '.stream')
        software_hwdec(child, field)

    audio = object_at(report, 'audio', 'audio')
    for name, vector in PLAYBACK_VECTORS['audio_vectors'].items():
        field = 'audio.' + name
        child = object_at(audio, name, field)
        equal(child, 'result', 'PASS', field)
        equal(child, 'sample_format', 's16', field)
        equal(child, 'sample_rate', vector['sample_rate'], field)
        equal(child, 'channels', vector['channels'], field)
        count(child, 'pcm_samples', vector['min_samples'], field)

    passthrough = object_at(report, 'passthrough', 'passthrough')
    for name in ('ac3', 'eac3'):
        field = 'passthrough.' + name
        child = object_at(passthrough, name, field)
        equal(child, 'result', 'PASS', field)
        equal(child, 'format', 'spdif-' + name, field)
        equal(child, 'reached_eof', True, field)
        # This is the observed lower bound checked by progress(), not a request
        # to the player. Retaining the bound keeps CPU reports deterministic.
        value = child.get('minimum_position')
        minimum = PLAYBACK_VECTORS['audio_vectors'][name]['minimum_position']
        require(type(value) in (int, float) and
                (type(value) is int or math.isfinite(value)) and value >= minimum,
                field + '.minimum_position must be finite and >= ' + str(minimum))

    # All mandatory children succeeded; their only consistent summary is PASS.
    equal(report, 'result', 'PASS', 'report')


def command(args, timeout=20):
    env = {k: v for k, v in os.environ.items()
           if k not in ('LD_LIBRARY_PATH', 'LD_PRELOAD', 'DISPLAY', 'WAYLAND_DISPLAY')}
    result = subprocess.run([str(x) for x in args], env=env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args[0].name} failed ({result.returncode}):\n{result.stdout[-3000:]}')
    return result.stdout


def query_features(root):
    versions = {}
    for name in ('mpv', 'ffmpeg', 'ffprobe'):
        flag = '--version' if name == 'mpv' else '-version'
        line = command([root / 'bin' / name, flag]).splitlines()[0]
        if not line.startswith(name + ' '):
            raise ValueError('Executable identity mismatch: ' + name)
        versions[name] = line
    accels = command([root / 'bin/ffmpeg', '-hide_banner', '-hwaccels']).splitlines()[1:]
    decoders = command([root / 'bin/ffmpeg', '-hide_banner', '-decoders'])
    muxers = command([root / 'bin/ffmpeg', '-hide_banner', '-muxers'])
    modes = command([root / 'bin/mpv', '--no-config', '--hwdec=help']).splitlines()
    contexts = command([root / 'bin/mpv', '--no-config', '--gpu-context=help'])
    probe = [pathlib.Path(sys.executable), '-I', '-c', LIBMPV_CONTEXT_PROBE, root / LIBMPV]
    library_contexts = json.loads(command(probe + GPU_CONTEXTS + ['nuvio-invalid-context']))
    library = (root / LIBMPV).read_bytes()
    return dict(
        program_versions=versions,
        software_decoders=[n for n in SOFTWARE if re.search(r'^\s*\S+\s+' + n + r'\s', decoders, re.M)],
        ffmpeg_hwaccels=sorted(x.strip() for x in accels if x.strip()),
        spdif_muxer=bool(re.search(r'^\s*E\s+spdif\s', muxers, re.M)),
        mpv_hwdec_modes=[x.strip() for x in modes if re.match(r'^\s+\S+\s+\(', x)],
        mpv_gpu_contexts=[n for n in GPU_CONTEXTS if re.search(r'^\s+' + n + r'\s', contexts, re.M)],
        libmpv_gpu_contexts=library_contexts,
        libmpv_wayland_interfaces=[n for n in WAYLAND_INTERFACES if n.encode() + b'\0' in library],
    )


def check_features(config, actual):
    if actual['software_decoders'] != SOFTWARE:
        raise ValueError('Missing software decoder')
    if not actual['spdif_muxer']:
        raise ValueError('Missing spdif muxer required by audio passthrough')
    for name in ('cuda', 'vaapi', 'drm', 'vulkan'):
        if name not in actual['ffmpeg_hwaccels']:
            raise ValueError('Missing hardware context: ' + name)
    for name in ('nvdec', 'vaapi', 'vaapi-copy', 'vulkan', 'vulkan-copy'):
        if not any(x.startswith(name + ' ') for x in actual['mpv_hwdec_modes']):
            raise ValueError('Missing mpv compiled mode: ' + name)
    for name in ('CONFIG_H264_NVDEC_HWACCEL', 'CONFIG_HEVC_VAAPI_HWACCEL',
                 'CONFIG_H264_VULKAN_HWACCEL', 'CONFIG_HEVC_VULKAN_HWACCEL',
                 'CONFIG_AV1_VULKAN_HWACCEL', 'CONFIG_SPDIF_MUXER'):
        if config['ffmpeg'].get(name) != '1':
            raise ValueError('Missing compile feature: ' + name)
    for name in ('HAVE_DRM', 'HAVE_VAAPI', 'HAVE_VAAPI_DRM', 'HAVE_CUDA_HWACCEL',
                 'HAVE_CUDA_INTEROP', 'HAVE_VULKAN', 'HAVE_X11', 'HAVE_WAYLAND',
                 'HAVE_WAYLAND_PROTOCOLS_1_41'):
        if config['mpv'].get(name) != '1':
            raise ValueError('Missing mpv feature: ' + name)
    # Fail closed unless both the player CLI and libmpv itself accept the X11
    # and Wayland Vulkan contexts, and libmpv carries color-management code.
    for key in ('mpv_gpu_contexts', 'libmpv_gpu_contexts'):
        if actual.get(key) != GPU_CONTEXTS:
            raise ValueError('Missing GPU context in ' + key + ': ' + repr(actual.get(key)))
    if actual.get('libmpv_wayland_interfaces') != WAYLAND_INTERFACES:
        raise ValueError('Missing Wayland protocol code in libmpv')
