#!/usr/bin/env python3
"""Private-bus contract tests for the Flatpak portal self-update. Usage: python3 this.py /path/to/probe.
A stand-in org.freedesktop.portal.Flatpak replays each outcome the real portal reports. The bus has no
service directories, so no real portal is ever activated; requires PyGObject and dbus-daemon.
"""
import os
import subprocess
import sys
import tempfile
import threading
from gi.repository import Gio, GLib

for key in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS'):
    os.environ.pop(key, None)

PORTAL = 'org.freedesktop.portal.Flatpak'
MONITOR = PORTAL + '.UpdateMonitor'
XML = f'''<node>
<interface name="{PORTAL}">
  <method name="CreateUpdateMonitor"><arg type="a{{sv}}" direction="in"/><arg type="o" direction="out"/></method>
</interface>
<interface name="{MONITOR}">
  <method name="Update"><arg type="s" direction="in"/><arg type="a{{sv}}" direction="in"/></method>
  <method name="Close"/>
  <signal name="Progress"><arg type="a{{sv}}"/></signal>
</interface></node>'''
INFO = Gio.DBusNodeInfo.new_for_xml(XML)
TYPES = {'status': 'u', 'op': 'u', 'n_ops': 'u', 'progress': 'u', 'error': 's', 'error_message': 's'}
DENIED = 'org.freedesktop.DBus.Error.AccessDenied'
SCENARIOS = {
    # name: (Progress signals after Update, probe idle timeout ms, expected progress lines, expected result)
    'updated': ([dict(status=0, op=0, n_ops=2, progress=50), dict(status=0, op=1, n_ops=2, progress=100),
                 dict(status=2, op=1, n_ops=2, progress=100)], 10000, ['0/2 50', '1/2 100'], '2||'),
    'nothing newer': ([dict(status=1)], 10000, [], '1||'),
    'done without operations': ([dict(status=2)], 10000, [], '1||'),
    'denied': ([dict(status=3, error=DENIED, error_message='Application update not allowed')], 10000, [],
               f'3|{DENIED}|Application update not allowed'),
    'silent portal': ([], 1500, [], '-2||the Flatpak portal stopped responding'),
}
CONFIG = '''<busconfig><type>session</type><listen>unix:dir={dir}</listen>
<policy context="default"><allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/><allow own="*"/></policy>
</busconfig>'''


def private_bus(directory):
    config = os.path.join(directory, 'bus.conf')
    with open(config, 'w') as handle:
        handle.write(CONFIG.format(dir=directory))
    daemon = subprocess.Popen(['dbus-daemon', '--config-file=' + config, '--nofork', '--print-address=1'],
                              stdout=subprocess.PIPE, text=True)
    return daemon, daemon.stdout.readline().strip()


def run_probe(address, timeout_ms):
    env = dict(os.environ, DBUS_SESSION_BUS_ADDRESS=address)
    result = subprocess.run([sys.argv[1], str(timeout_ms)], env=env, text=True, capture_output=True,
                            timeout=timeout_ms / 1000 + 15, check=True)
    lines = result.stdout.splitlines()
    return [line.removeprefix('progress ') for line in lines if line.startswith('progress ')], lines[-1]


def serve_portal(address, signals, calls, ready):
    # Only this thread runs the default main context; the probe is a separate process.
    loop = GLib.MainLoop()
    connection = Gio.DBusConnection.new_for_address_sync(
        address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION)

    def monitor_method(conn, sender, path, interface, name, args, invocation):
        calls.append((name, path))
        invocation.return_value(None)
        if name == 'Close':
            GLib.timeout_add(100, loop.quit)
            return
        for index, values in enumerate(signals):
            def emit(values=values):
                payload = {key: GLib.Variant(TYPES[key], value) for key, value in values.items()}
                conn.emit_signal(sender, path, MONITOR, 'Progress', GLib.Variant('(a{sv})', (payload,)))
                return False
            GLib.timeout_add(30 * (index + 1), emit)

    def portal_method(conn, sender, path, interface, name, args, invocation):
        token = args.unpack()[0].get('handle_token', 'x')
        calls.append((name, token))
        monitor = f'/org/freedesktop/portal/Flatpak/update_monitor/{sender[1:].replace(".", "_")}/{token}'
        conn.register_object(monitor, INFO.interfaces[1], monitor_method, None, None)
        invocation.return_value(GLib.Variant('(o)', (monitor,)))

    connection.register_object('/org/freedesktop/portal/Flatpak', INFO.interfaces[0], portal_method, None, None)
    connection.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
                         GLib.Variant('(su)', (PORTAL, 4)), None, Gio.DBusCallFlags.NONE, -1, None)
    ready.set()
    GLib.timeout_add_seconds(30, loop.quit)
    loop.run()
    connection.close_sync(None)


for name, (signals, timeout_ms, expected_progress, expected_result) in SCENARIOS.items():
    with tempfile.TemporaryDirectory(prefix='nuvio-flatpak-update-') as directory:
        daemon, address = private_bus(directory)
        try:
            calls, ready = [], threading.Event()
            portal = threading.Thread(target=serve_portal, args=(address, signals, calls, ready))
            portal.start()
            assert ready.wait(10), 'stand-in portal did not start'
            progress, result = run_probe(address, timeout_ms)
            portal.join(timeout=10)
            assert progress == expected_progress, (name, progress)
            assert result == 'result ' + expected_result, (name, result)
            names = [call[0] for call in calls]
            assert names == ['CreateUpdateMonitor', 'Update', 'Close'], (name, calls)
            assert calls[0][1] == 'nuvio_update' and calls[1][1] == calls[2][1], (name, calls)
            print(f'PASS {name}', flush=True)
        finally:
            daemon.terminate()
            daemon.wait(timeout=10)

# No portal on the bus (and nothing to activate), then no bus at all.
with tempfile.TemporaryDirectory(prefix='nuvio-flatpak-update-') as directory:
    daemon, address = private_bus(directory)
    try:
        progress, result = run_probe(address, 1000)
        assert progress == [] and result.startswith('result -1||Flatpak portal unavailable: '), result
        print('PASS missing portal', flush=True)
    finally:
        daemon.terminate()
        daemon.wait(timeout=10)
    progress, result = run_probe('unix:path=' + os.path.join(directory, 'missing'), 1000)
    assert progress == [] and result.startswith('result -1||session bus unavailable: '), result
    print('PASS missing bus', flush=True)
