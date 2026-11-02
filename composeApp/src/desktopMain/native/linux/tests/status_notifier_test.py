#!/usr/bin/env python3
"""Isolated session-bus contract test. Usage: python3 this.py /path/to/linux_status_notifier_probe.
Requires PyGObject and dbus-daemon; never connects to or changes the real desktop bus.
"""
import atexit
import os
import subprocess
import sys
import threading
import time
from gi.repository import Gio, GLib

for key in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS'):
    os.environ.pop(key, None)

WATCHER = 'org.kde.StatusNotifierWatcher'
XML = f'''<node><interface name="{WATCHER}">
<method name="RegisterStatusNotifierItem"><arg type="s" direction="in"/></method>
<property name="IsStatusNotifierHostRegistered" type="b" access="read"/>
</interface></node>'''
INFO = Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0]
loop = GLib.MainLoop()
threading.Thread(target=loop.run, daemon=True).start()


class Bus:
    def __init__(self):
        self.daemon = subprocess.Popen(['dbus-daemon', '--session', '--nofork', '--print-address=1'],
                                       stdout=subprocess.PIPE, text=True)
        atexit.register(self.daemon.kill)  # A failed assertion must not leave a daemon behind.
        self.address = self.daemon.stdout.readline().strip()
        self.connection = self.connect()
        self.watcher = None
        self.registered = []
        self.host = True

    def connect(self):
        bus = Gio.DBusConnection.new_for_address_sync(self.address,
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        bus.set_exit_on_close(False)
        return bus

    def start_watcher(self, host=True):
        self.host = host
        self.watcher = self.connect()
        def call(_c, sender, _p, _i, _method, args, invocation):
            self.registered.append(args.unpack()[0])
            invocation.return_value(None)
        def get(_c, _s, _p, _i, _name):
            return GLib.Variant('b', self.host)
        self.watcher.register_object('/StatusNotifierWatcher', INFO, call, get, None)
        reply = self.watcher.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
            'RequestName', GLib.Variant('(su)', (WATCHER, 4)), GLib.VariantType('(u)'), 0, 1000, None)
        assert reply.unpack()[0] == 1

    def stop_watcher(self):
        self.watcher.close_sync(None)
        self.watcher = None

    def close(self):
        self.connection.close_sync(None)
        if self.watcher:
            self.watcher.close_sync(None)
        self.daemon.kill()
        self.daemon.wait()


def probe(bus):
    return subprocess.Popen([sys.argv[1]], stdout=subprocess.PIPE, text=True, bufsize=1,
                            env=dict(os.environ, DBUS_SESSION_BUS_ADDRESS=bus.address))


def line(process):
    box = []
    reader = threading.Thread(target=lambda: box.append(process.stdout.readline().strip()), daemon=True)
    reader.start()
    reader.join(5)
    assert box, 'probe was silent'
    return box[0]


def wait_for(test):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if test():
            return
        time.sleep(0.02)
    raise AssertionError('timed out')


# Nothing to show the icon: no watcher, then a watcher with no host. Both must refuse.
for setup in [None, False]:
    bus = Bus()
    if setup is False:
        bus.start_watcher(host=False)
    process = probe(bus)
    assert line(process) == 'unavailable'
    assert line(process) == 'refused'
    assert process.wait(5) == 0
    assert not bus.registered
    bus.close()
    print('PASS refused with', 'no host' if setup is False else 'no watcher', flush=True)

bus = Bus()
bus.start_watcher()
process = probe(bus)
assert line(process) == 'available'
assert line(process) == 'opened'
wait_for(lambda: bus.registered)
name = bus.registered[0]
assert name == f'org.kde.StatusNotifierItem-{process.pid}-1', name


def item_call(method, args=None, reply=None, path='/StatusNotifierItem', iface='org.kde.StatusNotifierItem'):
    return bus.connection.call_sync(name, path, iface, method, args,
        GLib.VariantType(reply) if reply else None, 0, 2000, None)


def prop(iface, path, key):
    return bus.connection.call_sync(name, path, 'org.freedesktop.DBus.Properties', 'Get',
        GLib.Variant('(ss)', (iface, key)), GLib.VariantType('(v)'), 0, 2000, None).unpack()[0]


ITEM = 'org.kde.StatusNotifierItem'
assert prop(ITEM, '/StatusNotifierItem', 'Title') == 'Nuvio HTPC'
assert prop(ITEM, '/StatusNotifierItem', 'Status') == 'Active'
assert prop(ITEM, '/StatusNotifierItem', 'ItemIsMenu') is False
assert prop(ITEM, '/StatusNotifierItem', 'Menu') == '/StatusNotifierMenu'
pixmaps = prop(ITEM, '/StatusNotifierItem', 'IconPixmap')
assert [(w, h, len(b)) for w, h, b in pixmaps] == [(2, 2, 16)], pixmaps
everything = bus.connection.call_sync(name, '/StatusNotifierItem', 'org.freedesktop.DBus.Properties', 'GetAll',
    GLib.Variant('(s)', (ITEM,)), GLib.VariantType('(a{sv})'), 0, 2000, None).unpack()[0]
assert everything['ToolTip'][2] == 'Nuvio HTPC' and everything['AttentionIconPixmap'] == []
print('PASS item properties', flush=True)

MENU = 'com.canonical.dbusmenu'
assert prop(MENU, '/StatusNotifierMenu', 'Version') == 3
revision, (root, props, children) = item_call('GetLayout', GLib.Variant('(iias)', (0, -1, [])),
    '(u(ia{sv}av))', '/StatusNotifierMenu', MENU).unpack()
assert root == 0 and props.get('children-display') == 'submenu'
assert [c[0] for c in children] == [1, 2, 3, 4, 5], children
assert children[0][1]['label'] == 'Open Nuvio'
assert children[1][1]['type'] == 'separator'
assert children[2][1]['label'] == 'Nuvio 1.2.3' and children[2][1]['enabled'] is False
assert children[4][1]['label'] == 'Exit'
shallow = item_call('GetLayout', GLib.Variant('(iias)', (0, 0, [])), '(u(ia{sv}av))', '/StatusNotifierMenu', MENU).unpack()
assert shallow[1][2] == []
group = item_call('GetGroupProperties', GLib.Variant('(aias)', ([1, 5, 99], [])), '(a(ia{sv}))', '/StatusNotifierMenu', MENU).unpack()[0]
assert [g[0] for g in group] == [1, 5]
assert item_call('GetProperty', GLib.Variant('(is)', (1, 'label')), '(v)', '/StatusNotifierMenu', MENU).unpack()[0] == 'Open Nuvio'
assert item_call('AboutToShow', GLib.Variant('(i)', (0,)), '(b)', '/StatusNotifierMenu', MENU).unpack()[0] is False
print('PASS menu layout', flush=True)

item_call('Activate', GLib.Variant('(ii)', (1, 1)))
assert line(process) == 'event 1'
item_call('ContextMenu', GLib.Variant('(ii)', (1, 1)))  # The host shows the menu itself: nothing to do.
item_call('Scroll', GLib.Variant('(is)', (1, 'vertical')))
stamp = GLib.Variant('(isvu)', (1, 'hovered', GLib.Variant('s', ''), 0))
item_call('Event', stamp, None, '/StatusNotifierMenu', MENU)
item_call('Event', GLib.Variant('(isvu)', (1, 'clicked', GLib.Variant('i', 0), 0)), None, '/StatusNotifierMenu', MENU)
assert line(process) == 'event 1'
print('PASS activation and clicks', flush=True)

# The shell restarts: the window has to be told, and the icon registers again when it is back.
bus.stop_watcher()
assert line(process) == 'event 3'
bus.registered.clear()
bus.start_watcher()
assert line(process) == 'event 4'
assert bus.registered == [name]
print('PASS host loss and return', flush=True)

item_call('EventGroup', GLib.Variant('(a(isvu))', ([(5, 'clicked', GLib.Variant('s', ''), 0)],)), '(ai)', '/StatusNotifierMenu', MENU)
assert line(process) == 'event 2'
assert line(process) == 'closed'
assert process.wait(5) == 0
owner = bus.connection.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
    'NameHasOwner', GLib.Variant('(s)', (name,)), GLib.VariantType('(b)'), 0, 1000, None).unpack()[0]
assert owner is False
bus.close()
loop.quit()
print('PASS exit releases the name', flush=True)
