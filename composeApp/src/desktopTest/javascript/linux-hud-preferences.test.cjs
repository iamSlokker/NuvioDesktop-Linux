// The Linux HUD's preferences outlive the player: runs the fork:linux block from the top of controls.js
// against a fake window and localStorage. No browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const js = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');

const end = js.indexOf('// Known at first script run (from the page URL)');
const block = js.slice(0, end);

function harness(search) {
  const posted = [];
  class Storage {
    constructor() { this.values = {}; }
    getItem(key) { return key in this.values ? this.values[key] : null; }
    setItem(key, value) { this.values[key] = String(value); }
    removeItem(key) { delete this.values[key]; }
  }
  const localStorage = new Storage();
  const window = { localStorage, webkit: { messageHandlers: { player: { postMessage: message => posted.push(message) } } } };
  vm.runInNewContext(block, { window, location: { search }, URLSearchParams, Number, Object });
  return { localStorage, posted };
}

test('the block is the first thing in controls.js, ahead of every read', () => {
  assert.ok(js.startsWith('// fork:linux - HUD preferences'));
  assert.ok(end > 0 && end < js.indexOf('window.localStorage.getItem('), 'seeded before the first read');
});

test('saved values reach localStorage before the page reads them', () => {
  const { localStorage, posted } = harness('?ui=1&linuxHudPrefs=nuvioShowRemainingTime:true,nuvioSourcePanelWidth:640');
  assert.equal(localStorage.getItem('nuvioShowRemainingTime'), 'true');
  assert.equal(localStorage.getItem('nuvioSourcePanelWidth'), '640');
  assert.equal(localStorage.getItem('nuvioContextMenuCloseOnSelect'), null);
  assert.deepEqual(posted, [], 'seeding reports nothing back');
});

test('unknown keys in the URL are ignored', () => {
  const { localStorage } = harness('?linuxHudPrefs=somethingElse:1,nuvioSourcePanelWidth:640');
  assert.equal(localStorage.getItem('somethingElse'), null);
  assert.equal(localStorage.getItem('nuvioSourcePanelWidth'), '640');
});

test('each write is reported, as a number the bridge can carry', () => {
  const { localStorage, posted } = harness('?linuxHudPrefs=');
  localStorage.setItem('nuvioShowRemainingTime', 'true');
  localStorage.setItem('nuvioContextMenuCloseOnSelect', 'false');
  localStorage.setItem('nuvioSourcePanelWidth', '720');
  localStorage.removeItem('nuvioSourcePanelWidth');
  localStorage.setItem('unrelated', 'x');
  assert.deepEqual(JSON.parse(JSON.stringify(posted)), [
    { type: 'linuxHudPref_nuvioShowRemainingTime', value: 1 },
    { type: 'linuxHudPref_nuvioContextMenuCloseOnSelect', value: 0 },
    { type: 'linuxHudPref_nuvioSourcePanelWidth', value: 720 },
    { type: 'linuxHudPref_nuvioSourcePanelWidth', value: -1 },
  ]);
  for (const { type } of posted) assert.match(type, /^[A-Za-z0-9_]{1,128}$/, 'the overlay drops anything else');
  assert.equal(localStorage.getItem('unrelated'), 'x');
});

test('without the parameter, as on Windows, the block does nothing', () => {
  const { localStorage, posted } = harness('?ui=1');
  localStorage.setItem('nuvioShowRemainingTime', 'true');
  assert.deepEqual(posted, []);
});
