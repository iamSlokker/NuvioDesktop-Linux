#!/usr/bin/env python3
"""Private-bus contract tests for the portal file chooser. Usage: python3 this.py /path/to/probe.
A stand-in org.freedesktop.portal.Desktop and org.freedesktop.portal.Documents replay each outcome the
real portals report. The bus has no service directories, so no real portal is ever activated; requires
PyGObject and dbus-daemon.
"""
import os
import subprocess
import sys
import tempfile
import threading
from gi.repository import Gio, GLib

for key in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS'):
    os.environ.pop(key, None)

DESKTOP = 'org.freedesktop.portal.Desktop'
DESKTOP_PATH = '/org/freedesktop/portal/desktop'
CHOOSER = 'org.freedesktop.portal.FileChooser'
REQUEST = 'org.freedesktop.portal.Request'
DOCUMENTS = 'org.freedesktop.portal.Documents'
TRANSFER = 'org.freedesktop.portal.FileTransfer'
XML = f'''<node>
<interface name="{CHOOSER}">
  <method name="OpenFile"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="SaveFile"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="o" direction="out"/></method>
  <property name="version" type="u" access="read"/>
</interface>
<interface name="{REQUEST}">
  <method name="Close"/>
  <signal name="Response"><arg type="u"/><arg type="a{{sv}}"/></signal>
</interface>
<interface name="{TRANSFER}">
  <method name="RetrieveFiles"><arg type="s" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="as" direction="out"/></method>
</interface></node>'''
INFO = Gio.DBusNodeInfo.new_for_xml(XML)
DOC_URI = 'file:///run/user/1000/doc/ab12cd34/My%20Movies'
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


def run_probe(address, *args, timeout=15):
    env = dict(os.environ, DBUS_SESSION_BUS_ADDRESS=address)
    result = subprocess.run([sys.argv[1], *args], env=env, text=True, capture_output=True, timeout=timeout, check=True)
    return result.stdout.splitlines()[-1]


def serve_portal(address, behaviour, calls, ready, version=4):
    """behaviour: (response code or None to stay silent, results dict) sent after the method replies."""
    loop = GLib.MainLoop()
    connection = Gio.DBusConnection.new_for_address_sync(
        address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION)

    def request_method(conn, sender, path, interface, name, args, invocation):
        calls.append(('Close', path))
        invocation.return_value(None)
        GLib.timeout_add(100, loop.quit)

    def chooser_method(conn, sender, path, interface, name, args, invocation):
        parent, title, options = args.unpack()
        calls.append((name, parent, title, options))
        request = f'{DESKTOP_PATH}/request/{sender[1:].replace(".", "_")}/{options["handle_token"]}'
        conn.register_object(request, INFO.interfaces[1], request_method, None, None)
        invocation.return_value(GLib.Variant('(o)', (request,)))
        response, results = behaviour
        if response == 'die':
            GLib.timeout_add(30, loop.quit)
        elif response is not None:
            def emit():
                payload = {key: GLib.Variant('as', value) for key, value in results.items()}
                conn.emit_signal(sender, request, REQUEST, 'Response', GLib.Variant('(ua{sv})', (response, payload)))
                GLib.timeout_add(100, loop.quit)
                return False
            GLib.timeout_add(30, emit)

    def chooser_property(conn, sender, path, interface, name):
        return GLib.Variant('u', version)

    def transfer_method(conn, sender, path, interface, name, args, invocation):
        key, _ = args.unpack()
        calls.append((name, key))
        if key == 'good':
            invocation.return_value(GLib.Variant('(as)', (['/run/user/1000/doc/ab/clip é.mkv', '/run/user/1000/doc/cd/b.mp4'],)))
        else:
            invocation.return_dbus_error('org.freedesktop.DBus.Error.AccessDenied', 'Invalid transfer')
        GLib.timeout_add(100, loop.quit)

    connection.register_object(DESKTOP_PATH, INFO.interfaces[0], chooser_method, chooser_property, None)
    connection.register_object('/org/freedesktop/portal/documents', INFO.interfaces[2], transfer_method, None, None)
    for name in (DESKTOP, DOCUMENTS):
        connection.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
                             GLib.Variant('(su)', (name, 4)), None, Gio.DBusCallFlags.NONE, -1, None)
    ready.set()
    GLib.timeout_add_seconds(20, loop.quit)
    loop.run()
    connection.close_sync(None)


def scenario(name, args, behaviour, expected, check=None, version=4):
    with tempfile.TemporaryDirectory(prefix='nuvio-chooser-') as directory:
        daemon, address = private_bus(directory)
        try:
            calls, ready = [], threading.Event()
            portal = threading.Thread(target=serve_portal, args=(address, behaviour, calls, ready, version))
            portal.start()
            assert ready.wait(10), 'stand-in portal did not start'
            result = run_probe(address, *args)
            portal.join(timeout=25)
            if callable(expected):
                assert expected(result), (name, result)
            else:
                assert result == expected, (name, result)
            if check:
                check(calls)
            print(f'PASS {name}', flush=True)
        finally:
            daemon.terminate()
            daemon.wait(timeout=10)


def folder_request(calls):
    method, parent, title, options = calls[0]
    assert method == 'OpenFile' and parent == 'x11:2a00004' and title == 'Probe título', calls
    assert options['directory'] is True and options['modal'] is True, options
    assert options['handle_token'].startswith('nuvio_chooser_'), options
    assert bytes(options['current_folder']).rstrip(b'\0') == b'/run/user/1000/doc/ab12cd34/Vids', options
    assert 'filters' not in options and 'current_name' not in options, options


def save_request(calls):
    method, _, _, options = calls[0]
    assert method == 'SaveFile', calls
    assert options['current_name'] == 'nuvio-settings.zip', options
    assert options['filters'] == [('Backups', [(0, '*.zip'), (0, '*.ZIP')])], options
    assert 'directory' not in options, options


def timed_out(calls):
    assert [call[0] for call in calls] == ['OpenFile', 'Close'], calls


scenario('folder picked', ['folder', '10000', '/run/user/1000/doc/ab12cd34/Vids'], (0, {'uris': [DOC_URI]}),
         f'result 0|{DOC_URI}|', folder_request)
scenario('file picked', ['open', '10000'], (0, {'uris': ['file:///run/user/1000/doc/ef/a.zip']}),
         'result 0|file:///run/user/1000/doc/ef/a.zip|')
scenario('save picked', ['save', '10000', '', 'nuvio-settings.zip'], (0, {'uris': ['file:///run/user/1000/doc/gh/b.zip']}),
         'result 0|file:///run/user/1000/doc/gh/b.zip|', save_request)
scenario('cancelled', ['folder', '10000'], (1, {}), 'result 1||')
scenario('backend error', ['folder', '10000'], (2, {}), 'result 2||the file chooser portal reported an error')
scenario('nothing exported', ['folder', '10000'], (0, {'uris': []}),
         'result -3||the portal could not share that location with Nuvio')
scenario('dialog left open', ['folder', '1200'], (None, {}), 'result -2||the file chooser was left open too long', timed_out)
scenario('portal dies with the dialog open', ['folder', '10000'], ('die', {}),
         'result -1||the file chooser portal stopped running')
scenario('portal too old for folders', ['folder', '10000'], (0, {}),
         'result -4||this xdg-desktop-portal cannot choose folders (FileChooser version 2)', version=2)
scenario('files on a portal too old for folders', ['open', '10000'], (0, {'uris': ['file:///x/a.zip']}),
         'result 0|file:///x/a.zip|', version=2)
scenario('transfer redeemed', ['retrieve', '5000', 'good'], (None, {}),
         'retrieved 1||/run/user/1000/doc/ab/clip é.mkv|/run/user/1000/doc/cd/b.mp4',
         lambda calls: calls == [('RetrieveFiles', 'good')] or (_ for _ in ()).throw(AssertionError(calls)))
scenario('transfer refused', ['retrieve', '5000', 'stale'], (None, {}),
         'retrieved 0|the file transfer portal refused the drop: Invalid transfer')

# No portal on the bus (and nothing to activate), then no bus at all.
with tempfile.TemporaryDirectory(prefix='nuvio-chooser-') as directory:
    daemon, address = private_bus(directory)
    try:
        result = run_probe(address, 'folder', '1000')
        assert result.startswith('result -1||the file chooser portal is unavailable: '), result
        result = run_probe(address, 'retrieve', '1000', 'k')
        assert result.startswith('retrieved 0|the file transfer portal refused the drop: '), result
        print('PASS missing portal', flush=True)
    finally:
        daemon.terminate()
        daemon.wait(timeout=10)
    result = run_probe('unix:path=' + os.path.join(directory, 'missing'), 'folder', '1000')
    assert result.startswith('result -1||session bus unavailable: '), result
    print('PASS missing bus', flush=True)
