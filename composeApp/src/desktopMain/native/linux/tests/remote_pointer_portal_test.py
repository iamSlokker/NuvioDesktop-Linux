#!/usr/bin/env python3
"""Private-bus contract tests for the remote desktop portal pointer. Usage: python3 this.py /path/to/probe.
A stand-in org.freedesktop.portal.Desktop replays what the real RemoteDesktop portal answers. The bus has
no service directories, so no real portal is ever activated; requires PyGObject and dbus-daemon.
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
REMOTE = 'org.freedesktop.portal.RemoteDesktop'
REQUEST = 'org.freedesktop.portal.Request'
SESSION = 'org.freedesktop.portal.Session'
XML = f'''<node>
<interface name="{REMOTE}">
  <method name="CreateSession"><arg type="a{{sv}}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="SelectDevices"><arg type="o" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="Start"><arg type="o" direction="in"/><arg type="s" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="NotifyPointerMotion"><arg type="o" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="d" direction="in"/><arg type="d" direction="in"/></method>
  <method name="NotifyPointerButton"><arg type="o" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="in"/></method>
  <method name="NotifyPointerAxisDiscrete"><arg type="o" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="u" direction="in"/><arg type="i" direction="in"/></method>
  <method name="NotifyKeyboardKeycode"><arg type="o" direction="in"/><arg type="a{{sv}}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="in"/></method>
  <property name="version" type="u" access="read"/>
  <property name="AvailableDeviceTypes" type="u" access="read"/>
</interface>
<interface name="{REQUEST}">
  <method name="Close"/>
  <signal name="Response"><arg type="u"/><arg type="a{{sv}}"/></signal>
</interface>
<interface name="{SESSION}">
  <method name="Close"/>
  <signal name="Closed"><arg type="a{{sv}}"/></signal>
</interface></node>'''
INFO = Gio.DBusNodeInfo.new_for_xml(XML)
CONFIG = '''<busconfig><type>session</type><listen>unix:dir={dir}</listen>
<policy context="default"><allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/><allow own="*"/></policy>
</busconfig>'''
INPUTS = ('NotifyPointerMotion', 'NotifyPointerButton', 'NotifyPointerAxisDiscrete', 'NotifyKeyboardKeycode')


def private_bus(directory):
    config = os.path.join(directory, 'bus.conf')
    with open(config, 'w') as handle:
        handle.write(CONFIG.format(dir=directory))
    daemon = subprocess.Popen(['dbus-daemon', '--config-file=' + config, '--nofork', '--print-address=1'],
                              stdout=subprocess.PIPE, text=True)
    return daemon, daemon.stdout.readline().strip()


def run_probe(address, *args, timeout=20):
    env = dict(os.environ, DBUS_SESSION_BUS_ADDRESS=address)
    result = subprocess.run([sys.argv[1], *args], env=env, text=True, capture_output=True, timeout=timeout, check=True)
    return result.stdout.splitlines()


def serve_portal(address, plan, calls, ready):
    """plan: version, devices (AvailableDeviceTypes), answers {method: (code or None, results)},
    handle_type ('s' or 'o' for session_handle), close_after (inputs before the portal closes the session),
    linger (seconds the stand-in waits for a call that never comes)."""
    loop = GLib.MainLoop()
    connection = Gio.DBusConnection.new_for_address_sync(
        address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION)
    session = {}

    def request_method(conn, sender, path, interface, name, args, invocation):
        calls.append(('Request.Close', path))
        invocation.return_value(None)
        GLib.timeout_add(100, loop.quit)

    def session_method(conn, sender, path, interface, name, args, invocation):
        calls.append(('Session.Close', path))
        invocation.return_value(None)
        GLib.timeout_add(100, loop.quit)

    def answer(conn, sender, method, token, results):
        request = f'{DESKTOP_PATH}/request/{sender[1:].replace(".", "_")}/{token}'
        conn.register_object(request, INFO.interfaces[1], request_method, None, None)
        code, extra = plan['answers'].get(method, (0, {}))
        if code is not None:
            payload = dict(results)
            payload.update(extra)

            def emit():
                conn.emit_signal(sender, request, REQUEST, 'Response', GLib.Variant('(ua{sv})', (code, payload)))
                if code != 0:
                    GLib.timeout_add(1500, loop.quit)
                return False
            GLib.timeout_add(30, emit)
        return request

    def remote_method(conn, sender, path, interface, name, args, invocation):
        values = args.unpack()
        if name in INPUTS:
            calls.append((name, *values[2:]))
            invocation.return_value(None)
            session['inputs'] = session.get('inputs', 0) + 1
            if session['inputs'] == plan.get('close_after'):
                conn.emit_signal(sender, session['path'], SESSION, 'Closed', GLib.Variant('(a{sv})', ({},)))
            return
        options = values[-1]
        calls.append((name, *values[:-1], options))
        results = {}
        if name == 'CreateSession':
            path = f'{DESKTOP_PATH}/session/{sender[1:].replace(".", "_")}/{options["session_handle_token"]}'
            session['path'] = path
            conn.register_object(path, INFO.interfaces[2], session_method, None, None)
            results['session_handle'] = GLib.Variant(plan.get('handle_type', 's'), path)
        elif name == 'Start':
            results['devices'] = GLib.Variant('u', plan.get('shared', 3))
            if plan.get('token'):
                results['restore_token'] = GLib.Variant('s', plan['token'])
        invocation.return_value(GLib.Variant('(o)', (answer(conn, sender, name, options['handle_token'], results),)))

    def remote_property(conn, sender, path, interface, name):
        return GLib.Variant('u', plan['version'] if name == 'version' else plan['devices'])

    connection.register_object(DESKTOP_PATH, INFO.interfaces[0], remote_method, remote_property, None)
    connection.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
                         GLib.Variant('(su)', (DESKTOP, 4)), None, Gio.DBusCallFlags.NONE, -1, None)
    ready.set()
    GLib.timeout_add_seconds(plan.get('linger', 20), loop.quit)
    loop.run()
    connection.close_sync(None)


def scenario(name, args, plan, expected, check=None):
    plan = {'version': 2, 'devices': 7, 'answers': {}, **plan}
    with tempfile.TemporaryDirectory(prefix='nuvio-pointer-') as directory:
        daemon, address = private_bus(directory)
        try:
            calls, ready = [], threading.Event()
            portal = threading.Thread(target=serve_portal, args=(address, plan, calls, ready))
            portal.start()
            assert ready.wait(10), 'stand-in portal did not start'
            lines = run_probe(address, *args)
            portal.join(timeout=25)
            assert lines == expected, (name, lines)
            if check:
                check(calls)
            print(f'PASS {name}', flush=True)
        finally:
            daemon.terminate()
            daemon.wait(timeout=10)


def methods(calls):
    return [call[0] for call in calls]


def granted(calls):
    assert methods(calls) == ['CreateSession', 'SelectDevices', 'Start', 'NotifyPointerMotion', 'NotifyPointerButton',
                              'NotifyPointerButton', 'NotifyKeyboardKeycode', 'NotifyPointerAxisDiscrete',
                              'NotifyKeyboardKeycode', 'Session.Close'], calls
    create, select, start = calls[0], calls[1], calls[2]
    assert create[1]['handle_token'].startswith('nuvio_pointer_'), create
    session = select[1]
    assert session.endswith('/' + create[1]['session_handle_token']), (session, create)
    assert select[2]['types'] == 3 and select[2]['persist_mode'] == 2 and 'restore_token' not in select[2], select
    assert start[1] == session and start[2] == '', start
    assert calls[3][1:] == (12.5, -3.0), calls[3]
    assert calls[4][1:] == (272, 1) and calls[5][1:] == (272, 0), calls[4:6]
    assert calls[6][1:] == (42, 1) and calls[7][1:] == (0, -2) and calls[8][1:] == (42, 0), calls[6:9]
    assert calls[9][1] == session, calls[9]


def restored(calls):
    select = calls[1]
    assert select[2]['restore_token'] == 'tok-old' and select[2]['persist_mode'] == 2, select


def old_portal(calls):
    select = calls[1]
    assert 'persist_mode' not in select[2] and 'restore_token' not in select[2], select


def refused(calls):
    assert methods(calls) == ['CreateSession', 'SelectDevices', 'Start', 'Session.Close'], calls


def unanswered(calls):
    assert methods(calls) == ['CreateSession', 'SelectDevices', 'Start', 'Request.Close', 'Session.Close'], calls


def pointer_only(calls):
    assert calls[1][2]['types'] == 2, calls[1]


RUN = ['active 1', 'again 0', 'after 1', 'stopped 0']
scenario('granted, first time', ['10000'], {'token': 'tok-1'}, ['start 0|tok-1|', *RUN], granted)
scenario('granted from a restore token', ['10000', 'tok-old'], {'token': 'tok-2'}, ['start 0|tok-2|', *RUN], restored)
scenario('session handle as an object path', ['10000'], {'handle_type': 'o'}, ['start 0||', *RUN])
scenario('portal without persistence', ['10000', 'tok-old'], {'version': 1}, ['start 0||', *RUN], old_portal)
scenario('no keyboard offered', ['10000'], {'devices': 2}, ['start 0||', *RUN], pointer_only)
scenario('user refuses', ['10000'], {'answers': {'Start': (1, {})}},
         ['start 1||remote control was not allowed', 'active 0', 'stopped 0'], refused)
scenario('backend error', ['10000'], {'answers': {'SelectDevices': (2, {})}},
         ['start 2||the remote desktop portal reported an error on the devices', 'active 0', 'stopped 0'])
scenario('pointer not shared', ['10000'], {'shared': 1},
         ['start -4||the pointer was not shared', 'active 0', 'stopped 0'])
scenario('no pointer on offer', ['10000'], {'devices': 1, 'linger': 2},
         ['start -4||the remote desktop portal offers no pointer', 'active 0', 'stopped 0'],
         lambda calls: calls == [] or (_ for _ in ()).throw(AssertionError(calls)))
scenario('dialog left open', ['1200'], {'answers': {'Start': (None, {})}},
         ['start -2||the remote control permission was left unanswered', 'active 0', 'stopped 0'], unanswered)
scenario('portal ends the session', ['10000'], {'close_after': 3},
         ['start 0||', 'active 1', 'again 0', 'after 0', 'stopped 0'])

# No portal on the bus (and nothing to activate), then no bus at all.
with tempfile.TemporaryDirectory(prefix='nuvio-pointer-') as directory:
    daemon, address = private_bus(directory)
    try:
        lines = run_probe(address, '1000')
        assert lines[0].startswith('start -1||the remote desktop portal is unavailable: ') and lines[1:] == ['active 0', 'stopped 0'], lines
        print('PASS missing portal', flush=True)
    finally:
        daemon.terminate()
        daemon.wait(timeout=10)
    lines = run_probe('unix:path=' + os.path.join(directory, 'missing'), '1000')
    assert lines[0].startswith('start -1||session bus unavailable: '), lines
    print('PASS missing bus', flush=True)
print('REMOTE_POINTER_PORTAL_PASS', flush=True)
