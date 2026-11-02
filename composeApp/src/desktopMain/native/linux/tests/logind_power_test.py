#!/usr/bin/env python3
"""Fake logind on a private bus. Usage: python3 this.py /path/to/linux_logind_power_probe.
Requires PyGObject and dbus-daemon. The probe's system bus address points at the private daemon,
so nothing here can reach the real logind or power anything off.
"""
import atexit
import os
import subprocess
import sys
import threading
from gi.repository import Gio, GLib

for key in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS'):
    os.environ.pop(key, None)

NAME = 'org.freedesktop.login1'
XML = '''<node><interface name="org.freedesktop.login1.Manager">
<method name="CanPowerOff"><arg type="s" direction="out"/></method>
<method name="PowerOff"><arg type="b" direction="in"/></method>
</interface></node>'''
INFO = Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0]
loop = GLib.MainLoop()
threading.Thread(target=loop.run, daemon=True).start()

for answer, refuse, can, issued in [('yes', False, 'can', 'issued'), ('challenge', False, 'cannot', 'issued'),
                                    ('na', False, 'cannot', 'issued'), ('yes', True, 'can', 'refused')]:
    daemon = subprocess.Popen(['dbus-daemon', '--session', '--nofork', '--print-address=1'], stdout=subprocess.PIPE, text=True)
    atexit.register(daemon.kill)
    address = daemon.stdout.readline().strip()
    bus = Gio.DBusConnection.new_for_address_sync(address,
        Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
    bus.set_exit_on_close(False)
    calls = []
    def call(_c, _s, _p, _i, method, args, invocation, answer=answer, refuse=refuse, calls=calls):
        if method == 'CanPowerOff':
            invocation.return_value(GLib.Variant('(s)', (answer,)))
        elif refuse:
            invocation.return_dbus_error('org.freedesktop.login1.BlockedByInhibitorLock', 'Inhibited')
        else:
            calls.append(args.unpack()[0])
            invocation.return_value(None)
    bus.register_object('/org/freedesktop/login1', INFO, call, None, None)
    bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
        GLib.Variant('(su)', (NAME, 4)), GLib.VariantType('(u)'), 0, 1000, None)
    result = subprocess.run([sys.argv[1]], text=True, capture_output=True, check=True, timeout=15,
                            env=dict(os.environ, DBUS_SYSTEM_BUS_ADDRESS=address))
    assert result.stdout.split() == [can, issued], result
    # interactive=false: a prompt would wait for someone who has left the room.
    assert calls == ([] if refuse else [False]), calls
    bus.close_sync(None)
    daemon.kill()
    daemon.wait()
    print(f'PASS CanPowerOff={answer} refuse={refuse}', flush=True)

# No logind at all.
daemon = subprocess.Popen(['dbus-daemon', '--session', '--nofork', '--print-address=1'], stdout=subprocess.PIPE, text=True)
atexit.register(daemon.kill)
address = daemon.stdout.readline().strip()
result = subprocess.run([sys.argv[1]], text=True, capture_output=True, check=True, timeout=15,
                        env=dict(os.environ, DBUS_SYSTEM_BUS_ADDRESS=address))
assert result.stdout.split() == ['cannot', 'refused'], result
daemon.kill()
daemon.wait()
loop.quit()
print('PASS no logind', flush=True)
