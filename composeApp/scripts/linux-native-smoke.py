#!/usr/bin/env python3
"""Isolated launches of the Linux app (native image or JVM) for the native-image tasks.

Runs only inside a throwaway container (linuxNativeSmoke / linuxNativeImageTrace start it with
podman): every launch gets a private Xvfb display, a private D-Bus session daemon and an empty
HOME/XDG profile under --out, never the developer's desktop or Nuvio profile.

  smoke   [--runs N] -- <bin/nuvio>   launches with NUVIO_NATIVE_SELFTEST; fails without a painted
                                      first frame, without "(NativeSelfTest) done ... failed=0", on
                                      an early exit, or on a native-image failure in the console
  measure [--runs N] -- <command>     first-frame time only (no self-test), for JVM/native comparisons
  trace   [--linger S] -- <command>   one launch with the self-test, kept open S seconds, then a
                                      clean SIGTERM so the tracing agent writes its output
"""
import argparse
import ctypes
import ctypes.util
import os
import re
import select
import shutil
import signal
import subprocess
import sys
import threading
import time

FAILURE_PATTERNS = [
    re.compile(p) for p in (
        r'Missing\w*Registration',         # MissingReflectionRegistrationError, QA warnings, ...
        r'without it being registered',
        r'Fatal error',                     # SVM fatal error / HotSpot hs_err banner
        r'SIGSEGV|Segmentation fault',
        r'UnsatisfiedLinkError',
        r'NoClassDefFoundError',
        r'ExceptionInInitializerError',
    )
]
# The image's settings for a software-only X server (linux-native-smoke.Containerfile).
PASS_THROUGH = ('NUVIO_NATIVE_SELFTEST_', 'WEBKIT_', 'LIBGL_', 'MESA_', 'VK_')
SELFTEST_DONE = re.compile(r'\(NativeSelfTest\) done passed=(\d+) failed=(\d+)')
SELFTEST_LINE = re.compile(r'\(NativeSelfTest\) (.*)')


def in_container():
    return os.path.exists('/run/.containerenv') or os.path.exists('/.dockerenv')


# ---- First painted frame, read from the private X server through Xlib ---------------------------
class XWindowAttributes(ctypes.Structure):
    _fields_ = [('x', ctypes.c_int), ('y', ctypes.c_int), ('width', ctypes.c_int), ('height', ctypes.c_int),
                ('border_width', ctypes.c_int), ('depth', ctypes.c_int), ('visual', ctypes.c_void_p),
                ('root', ctypes.c_ulong), ('c_class', ctypes.c_int), ('bit_gravity', ctypes.c_int),
                ('win_gravity', ctypes.c_int), ('backing_store', ctypes.c_int), ('backing_planes', ctypes.c_ulong),
                ('backing_pixel', ctypes.c_ulong), ('save_under', ctypes.c_int), ('colormap', ctypes.c_ulong),
                ('map_installed', ctypes.c_int), ('map_state', ctypes.c_int), ('all_event_masks', ctypes.c_long),
                ('your_event_mask', ctypes.c_long), ('do_not_propagate_mask', ctypes.c_long),
                ('override_redirect', ctypes.c_int), ('screen', ctypes.c_void_p)]


class XImage(ctypes.Structure):
    _fields_ = [('width', ctypes.c_int), ('height', ctypes.c_int), ('xoffset', ctypes.c_int), ('format', ctypes.c_int),
                ('data', ctypes.POINTER(ctypes.c_ubyte)), ('byte_order', ctypes.c_int), ('bitmap_unit', ctypes.c_int),
                ('bitmap_bit_order', ctypes.c_int), ('bitmap_pad', ctypes.c_int), ('depth', ctypes.c_int),
                ('bytes_per_line', ctypes.c_int), ('bits_per_pixel', ctypes.c_int)]


class FrameWatcher:
    """A top-level window of at least 300x200 whose centre shows more than three colours."""

    def __init__(self, display):
        x = self.x = ctypes.CDLL(ctypes.util.find_library('X11'))
        x.XOpenDisplay.restype = ctypes.c_void_p
        x.XOpenDisplay.argtypes = [ctypes.c_char_p]
        x.XDefaultRootWindow.restype = ctypes.c_ulong
        x.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
        x.XQueryTree.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong),
                                 ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.POINTER(ctypes.c_ulong)),
                                 ctypes.POINTER(ctypes.c_uint)]
        x.XGetWindowAttributes.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(XWindowAttributes)]
        x.XGetImage.restype = ctypes.POINTER(XImage)
        x.XGetImage.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_int, ctypes.c_uint,
                                ctypes.c_uint, ctypes.c_ulong, ctypes.c_int]
        x.XFree.argtypes = [ctypes.c_void_p]
        x.XCloseDisplay.argtypes = [ctypes.c_void_p]
        x.XSetErrorHandler.argtypes = [ctypes.c_void_p]
        self.handler = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)(lambda d, e: 0)
        x.XSetErrorHandler(ctypes.cast(self.handler, ctypes.c_void_p))
        self.dpy = x.XOpenDisplay(display.encode())
        if not self.dpy:
            raise RuntimeError('cannot open private display ' + display)
        self.root = x.XDefaultRootWindow(self.dpy)

    def painted(self):
        x = self.x
        r, p, kids, n = ctypes.c_ulong(), ctypes.c_ulong(), ctypes.POINTER(ctypes.c_ulong)(), ctypes.c_uint()
        if not x.XQueryTree(self.dpy, self.root, ctypes.byref(r), ctypes.byref(p), ctypes.byref(kids), ctypes.byref(n)):
            return False
        try:
            for i in range(n.value):
                a = XWindowAttributes()
                if not x.XGetWindowAttributes(self.dpy, kids[i], ctypes.byref(a)) or a.map_state != 2 \
                        or a.width < 300 or a.height < 200:
                    continue
                w, h = min(256, a.width), min(256, a.height)
                img = x.XGetImage(self.dpy, kids[i], (a.width - w) // 2, (a.height - h) // 2, w, h, 0xFFFFFFFF, 2)
                if not img:
                    continue
                im = img.contents
                seen = set()
                for row in range(0, h, 8):
                    base = row * im.bytes_per_line
                    for col in range(0, w * 4, 32):
                        seen.add(tuple(im.data[base + col:base + col + 3]))
                x.XFree(ctypes.cast(im.data, ctypes.c_void_p))
                x.XFree(ctypes.cast(img, ctypes.c_void_p))
                if len(seen) > 3:
                    return True
        finally:
            if kids:
                x.XFree(kids)
        return False

    def close(self):
        self.x.XCloseDisplay(self.dpy)


# ---- One isolated launch ---------------------------------------------------------------------------
def image_root_for(command):
    """bin/nuvio or lib/nuvio of a package layout -> its root; None for anything else."""
    exe = os.path.realpath(command[0])
    parent = os.path.dirname(exe)
    if os.path.basename(parent) in ('bin', 'lib'):
        return os.path.dirname(parent)
    return None


class Launch:
    def __init__(self, index, out, command, selftest, image_root, display_number, extra_env=()):
        self.extra_env = dict(item.split('=', 1) for item in extra_env)
        self.dir = os.path.join(out, 'run-%d' % index)
        self.command = command
        self.selftest = selftest
        self.image_root = image_root
        self.display = ':%d' % display_number
        self.lines = []
        self.done = None
        self.processes = []

    def environment(self):
        profile = {name: os.path.join(self.dir, name) for name in ('home', 'config', 'data', 'cache', 'tmp', 'run')}
        for path in profile.values():
            os.makedirs(path, exist_ok=True)
        os.chmod(profile['run'], 0o700)
        env = {
            'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
            'HOME': profile['home'], 'XDG_CONFIG_HOME': profile['config'], 'XDG_DATA_HOME': profile['data'],
            'XDG_CACHE_HOME': profile['cache'], 'TMPDIR': profile['tmp'], 'XDG_RUNTIME_DIR': profile['run'],
            'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC', 'DISPLAY': self.display,
            # What the packages' runtime wrapper sets (tools/linux/packaging/nuvio-desktop).
            'GDK_BACKEND': 'x11',
        }
        if self.image_root:
            lib = os.path.join(self.image_root, 'lib')
            env['LD_LIBRARY_PATH'] = ':'.join(
                os.path.join(lib, sub) for sub in ('native', 'runtime/lib', 'runtime/lib/server'))
        env.update({key: value for key, value in os.environ.items() if key.startswith(PASS_THROUGH)})
        env.update(self.extra_env)
        if self.selftest:
            env['NUVIO_NATIVE_SELFTEST'] = '1'
            env['NUVIO_NATIVE_SELFTEST_DATA_DIR'] = os.path.join(profile['data'], 'nuvio-selftest')
        return env

    def start_services(self, env):
        number = self.display[1:]
        xvfb = subprocess.Popen(['Xvfb', self.display, '-screen', '0', '1920x1080x24', '-nolisten', 'tcp', '-noreset'],
                                stdout=subprocess.DEVNULL, stderr=open(os.path.join(self.dir, 'xvfb.log'), 'w'))
        self.processes.append(xvfb)
        socket = '/tmp/.X11-unix/X' + number
        for _ in range(200):
            if os.path.exists(socket):
                break
            time.sleep(0.05)
        else:
            self.stop_services()
            raise RuntimeError('Xvfb did not start on ' + self.display)
        bus = subprocess.Popen(['dbus-daemon', '--session', '--nofork', '--print-address=1'],
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=env)
        self.processes.append(bus)
        # A dbus-daemon that never prints its address would otherwise block this read forever.
        ready, _, _ = select.select([bus.stdout], [], [], 30)
        address = bus.stdout.readline().strip() if ready else ''
        if not address:
            self.stop_services()
            raise RuntimeError('dbus-daemon did not report a session bus address')
        env['DBUS_SESSION_BUS_ADDRESS'] = address

    def stop_services(self):
        for process in reversed(self.processes):
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()

    def run(self, timeout, linger, want_frame=True):
        os.makedirs(self.dir, exist_ok=True)
        env = self.environment()
        self.start_services(env)
        watcher = FrameWatcher(self.display)
        console = open(os.path.join(self.dir, 'console.log'), 'w', errors='replace')
        start = time.monotonic()
        app = subprocess.Popen(self.command, env=env, cwd=env['HOME'], stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace',
                               start_new_session=True)

        def read():
            for line in app.stdout:
                console.write(line)
                console.flush()
                self.lines.append(line.rstrip('\n'))
                match = SELFTEST_DONE.search(line)
                if match:
                    self.done = (int(match.group(1)), int(match.group(2)))
        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        self.first_frame_ms = None
        deadline = start + timeout
        while time.monotonic() < deadline and app.poll() is None:
            if want_frame and self.first_frame_ms is None and watcher.painted():
                self.first_frame_ms = int((time.monotonic() - start) * 1000)
            if (self.first_frame_ms is not None or not want_frame) and (not self.selftest or self.done):
                break
            time.sleep(0.02)
        watcher.close()
        self.exited_early = app.poll() is not None
        end = time.monotonic() + linger
        while time.monotonic() < end and app.poll() is None:
            time.sleep(0.2)
        if app.poll() is None:
            os.killpg(app.pid, signal.SIGTERM)
            try:
                app.wait(timeout=60)
            except subprocess.TimeoutExpired:
                os.killpg(app.pid, signal.SIGKILL)
                app.wait()
        else:
            # Anything the app left in its session (WebKit helpers) goes too.
            try:
                os.killpg(app.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        reader.join(timeout=10)
        self.exit_code = app.returncode
        console.close()
        self.stop_services()
        return self

    def failures(self):
        problems = []
        if self.first_frame_ms is None:
            problems.append('no painted first frame')
        if self.selftest:
            if self.done is None:
                problems.append('self-test did not finish' + (' (process exited %s)' % self.exit_code if self.exited_early else ''))
            elif self.done[1]:
                problems.append('%d self-test probe(s) failed' % self.done[1])
        elif self.exited_early:
            problems.append('process exited early (%s)' % self.exit_code)
        for line in self.lines:
            if any(p.search(line) for p in FAILURE_PATTERNS):
                problems.append('console: ' + line.strip()[:300])
        return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('mode', choices=('smoke', 'measure', 'trace'))
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--timeout', type=float, default=150)
    parser.add_argument('--linger', type=float, default=None)
    parser.add_argument('--out', required=True)
    parser.add_argument('--env', action='append', default=[], metavar='KEY=VALUE',
                        help='extra environment for the app (repeatable)')
    argv = sys.argv[1:]
    split = argv.index('--') if '--' in argv else len(argv)
    args = parser.parse_args(argv[:split])
    command = argv[split + 1:]
    if not command:
        parser.error('missing command after --')
    if not in_container() and os.environ.get('NUVIO_NATIVE_SMOKE_HOST_XVFB') != '1':
        sys.exit('Refusing to run outside a container: GUI launches belong in a throwaway container '
                 '(set NUVIO_NATIVE_SMOKE_HOST_XVFB=1 only on a disposable CI machine).')
    for name in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS'):
        os.environ.pop(name, None)
    os.makedirs(args.out, exist_ok=True)
    image_root = image_root_for(command)
    if args.mode == 'trace':
        runs, selftest, linger = 1, True, 60 if args.linger is None else args.linger
    elif args.mode == 'measure':
        runs, selftest, linger = args.runs, False, 3 if args.linger is None else args.linger
    else:
        runs, selftest, linger = args.runs, True, 5 if args.linger is None else args.linger
    failed = False
    for index in range(1, runs + 1):
        launch = Launch(index, args.out, command, selftest, image_root, 70 + index, args.env).run(args.timeout, linger)
        for line in launch.lines:
            match = SELFTEST_LINE.search(line)
            if match:
                print('  run %d: %s' % (index, match.group(1)[:200]))
        problems = launch.failures() if args.mode == 'smoke' else []
        print('RUN %d FIRST_FRAME_MS %s SELFTEST %s EXIT %s%s' % (
            index, launch.first_frame_ms if launch.first_frame_ms is not None else 'none',
            'passed=%d failed=%d' % launch.done if launch.done else 'none', launch.exit_code,
            '' if not problems else ' FAIL'), flush=True)
        for problem in problems[:40]:
            print('    ' + problem, flush=True)
        failed = failed or bool(problems)
        if args.mode != 'trace' and not problems:
            # A passing run keeps its logs, not its profile; a failing run keeps everything.
            for name in ('home', 'config', 'data', 'cache', 'tmp', 'run'):
                shutil.rmtree(os.path.join(launch.dir, name), ignore_errors=True)
    if args.mode == 'smoke':
        print('SMOKE %s (%d run(s), logs in %s)' % ('FAIL' if failed else 'PASS', runs, args.out), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
