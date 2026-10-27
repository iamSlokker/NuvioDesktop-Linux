// The HUD's linux-host class, from the real controls.js line, against the user agents it meets.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');
const line = source.match(/^const linuxHost = .*;$/m);
assert.ok(line, 'production linux-host detection must exist');

const linuxHost = userAgent => vm.runInNewContext(`${line[0]}\nlinuxHost;`, { navigator: { userAgent } });

test('WebKitGTK is a Linux host, branded by the distribution or not', () => {
  // Flatpak (GNOME runtime) and AppImage (own unbranded build); the DEB on Ubuntu and on Fedora.
  assert.equal(linuxHost('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15'), true);
  assert.equal(linuxHost('Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/60.5 Safari/605.1.15'), true);
  assert.equal(linuxHost('Mozilla/5.0 (X11; Fedora; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15'), true);
});

test('Windows and macOS hosts are not', () => {
  assert.equal(linuxHost('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0'), false);
  assert.equal(linuxHost('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)'), false);
});
