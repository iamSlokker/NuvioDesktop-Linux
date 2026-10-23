#!/usr/bin/env python3
"""Native smoke gate on the staged package image, inside the pinned builder only.

Runs composeApp/scripts/linux-native-smoke.py (stage 1's gate) against
<work>/canonical/bin/nuvio-desktop, the packages' own launcher: every launch gets
a private Xvfb, a private D-Bus daemon and an empty profile, in a container run
without network (build.build_in_builder). It fails on a missing first frame, any
failed self-test probe, an early exit or a missing-registration/fatal error in
the console. Only a pass writes <work>/native-image/smoke.json, which formats,
flatpak.py and verify.py require for the exact executable (and DEB payload).
Never run this against a desktop session: it refuses outside a container.
"""
import ctypes
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import native_evidence

RUN = re.compile(r'^RUN (\d+) FIRST_FRAME_MS (\d+|none) SELFTEST (?:passed=(\d+) failed=(\d+)|none) EXIT (-?\d+|None)( FAIL)?$')
CPU_DEVICE = 4  # VK_PHYSICAL_DEVICE_TYPE_CPU


def software_vulkan_device(loader):
    """Name of the CPU (lavapipe) Vulkan device, through the image's own Vulkan loader.

    libplacebo never picks a software device by itself; the self-test names it
    (NUVIO_NATIVE_SELFTEST_MPV_OPTIONS). The name carries the CPU's vector width,
    so it is read here rather than pinned.
    """
    vk = ctypes.CDLL(str(loader))

    class InstanceCreateInfo(ctypes.Structure):
        _fields_ = [('sType', ctypes.c_int), ('pNext', ctypes.c_void_p), ('flags', ctypes.c_uint32),
                    ('pApplicationInfo', ctypes.c_void_p), ('enabledLayerCount', ctypes.c_uint32),
                    ('ppEnabledLayerNames', ctypes.c_void_p), ('enabledExtensionCount', ctypes.c_uint32),
                    ('ppEnabledExtensionNames', ctypes.c_void_p)]

    vk.vkCreateInstance.argtypes = [ctypes.POINTER(InstanceCreateInfo), ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
    vk.vkCreateInstance.restype = ctypes.c_int
    vk.vkEnumeratePhysicalDevices.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32), ctypes.c_void_p]
    vk.vkEnumeratePhysicalDevices.restype = ctypes.c_int
    vk.vkGetPhysicalDeviceProperties.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    vk.vkDestroyInstance.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    instance = ctypes.c_void_p()
    if vk.vkCreateInstance(ctypes.byref(InstanceCreateInfo(sType=1)), None, ctypes.byref(instance)) != 0:
        raise RuntimeError('vkCreateInstance failed: no Vulkan driver for the smoke gate')
    try:
        count = ctypes.c_uint32()
        vk.vkEnumeratePhysicalDevices(instance, ctypes.byref(count), None)
        devices = (ctypes.c_void_p * count.value)()
        vk.vkEnumeratePhysicalDevices(instance, ctypes.byref(count), devices)
        names = []
        for device in devices:
            # VkPhysicalDeviceProperties: 5 uint32 fields, then char deviceName[256] (824 bytes in all).
            properties = ctypes.create_string_buffer(1024)
            vk.vkGetPhysicalDeviceProperties(device, properties)
            kind = int.from_bytes(properties.raw[16:20], 'little')
            name = properties.raw[20:276].split(b'\0', 1)[0].decode()
            if kind == CPU_DEVICE and name.startswith('llvmpipe'):
                names.append(name)
    finally:
        vk.vkDestroyInstance(instance, None)
    if len(names) != 1:
        raise RuntimeError('Expected exactly one lavapipe device, found %r' % names)
    return names[0]


def parse_runs(output):
    runs = []
    for line in output.splitlines():
        match = RUN.match(line.strip())
        if match:
            runs.append(dict(run=int(match[1]), first_frame_ms=None if match[2] == 'none' else int(match[2]),
                             passed=None if match[3] is None else int(match[3]),
                             failed=None if match[4] is None else int(match[4]),
                             exit=match[5], failed_checks=bool(match[6])))
    return runs


# Each run is bounded inside linux-native-smoke.py: 150 s to the first frame and self-test, 5 s
# linger, 60 s for SIGTERM, then service shutdown. This outer bound only catches a hang those
# waits cannot see, so a stuck launch fails the step instead of holding the job for hours.
SMOKE_RUN_BOUND_SECONDS = 300


def smoke_deadline_seconds(runs):
    return runs * SMOKE_RUN_BOUND_SECONDS + 60


def smoke(work, source=Path('/source'), runs=3):
    work = Path(work)
    canonical = work / 'canonical'
    evidence = work / 'native-image'
    (evidence / 'smoke.json').unlink(missing_ok=True)
    native_evidence.verify(canonical, evidence)
    if any(os.environ.get(name) for name in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS')):
        raise SystemExit('Refusing: a display or live-test variable is set')
    before = native_evidence.image_identity(canonical)
    device = software_vulkan_device(canonical / 'lib/native/libvulkan.so.1')
    env = {key: value for key, value in os.environ.items()
           if key not in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS', 'LD_LIBRARY_PATH', 'LD_PRELOAD')}
    # As linux-native-smoke.Containerfile: software X server, so no accelerated WebKit compositing.
    env.update(WEBKIT_DISABLE_COMPOSITING_MODE='1', NUVIO_NATIVE_SELFTEST_MPV_OPTIONS='vulkan-device=' + device)
    out = work / 'native-smoke'
    script = source / 'composeApp/scripts/linux-native-smoke.py'
    try:
        result = subprocess.run([sys.executable, '-I', str(script), 'smoke', '--runs', str(runs), '--out', str(out),
                                 '--', str(canonical / 'bin/nuvio-desktop')],
                                env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                timeout=smoke_deadline_seconds(runs))
    except subprocess.TimeoutExpired as hung:
        print(hung.output or '', end='', flush=True)
        raise SystemExit('Native smoke gate FAILED: hung past %ds; no package may be built (logs in %s)'
                         % hung.timeout, out)
    print(result.stdout, end='', flush=True)
    records = parse_runs(result.stdout)
    if (result.returncode != 0 or 'SMOKE PASS' not in result.stdout or len(records) != runs
            or any(r['failed_checks'] or r['failed'] != 0 or not r['passed'] for r in records)):
        raise SystemExit('Native smoke gate FAILED; no package may be built (logs in %s)' % out)
    if native_evidence.image_identity(canonical) != before:
        raise SystemExit('The smoke run modified the staged image')
    manifest = json.loads((evidence / 'inputs.json').read_text())
    record = dict(status='PASS', runs=records, vulkan_device=device,
                  executable_sha256=manifest['executable']['sha256'], image_identity=before,
                  graalvm=manifest['graalvm']['GRAALVM_VERSION'],
                  scope='Private Xvfb/D-Bus/profile in the package builder without network; '
                        'software rendering; not desktop, hardware or signed-in acceptance')
    (evidence / 'smoke.json').write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
    return record


if __name__ == '__main__':
    smoke(Path(sys.argv[1]))
