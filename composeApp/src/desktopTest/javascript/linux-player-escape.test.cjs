// Execute the actual controls.js Escape policy and document listener; no browser/window/network.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');
const start = source.indexOf('function handlePlayerEscape(');
assert.ok(start >= 0, 'production AWT Escape bridge policy must exist');
const end = source.indexOf('\nsetProgress(0, 0);', start);
assert.ok(end > start);
const actual = source.slice(start, end);

function fixture(overrides = {}) {
  const calls = [];
  let listener;
  const ctx = {
    isHeroTrailerSurface: false, state: { heroTrailerMode: false, skipSubmitToastDismissible: false },
    subtitleHexField: '', contextMenuOpen: false, actionOverflowOpen: false,
    colorGradePanelOpen: false, activeModal: '', error: '', window: {},
    document: { addEventListener(type, fn) { assert.equal(type, 'keydown'); listener = fn; } },
    playbackErrorText: () => ctx.error,
    closeSubtitleHexPrompt: () => { calls.push('hex'); ctx.subtitleHexField = ''; },
    closeContextMenu: () => { calls.push('context'); ctx.contextMenuOpen = false; },
    setActionOverflowOpen: value => { assert.equal(value, false); calls.push('overflow'); ctx.actionOverflowOpen = false; },
    setColorGradePanelOpen: value => { assert.equal(value, false); calls.push('color'); ctx.colorGradePanelOpen = false; },
    closePlayerModal: notify => { assert.equal(notify, true); calls.push('modal'); ctx.activeModal = ''; },
    focusShortcutRoot: () => calls.push('focus'), send: (type, value) => { assert.equal(value, 0); calls.push(type); },
    ...overrides,
  };
  vm.runInNewContext(actual, ctx);
  return { ctx, calls, linux: () => ctx.window.nuvioHandleLinuxEscape(),
    focused: () => listener({ key: 'Escape', preventDefault() { calls.push('prevent'); } }) };
}

test('AWT entry reaches shared Back without DOM focus', () => {
  const f = fixture(); f.linux(); assert.deepEqual(f.calls, ['back']);
});
test('one gesture closes only the topmost layer', () => {
  const f = fixture({ subtitleHexField: 'textColor', contextMenuOpen: true, actionOverflowOpen: true,
    colorGradePanelOpen: true, activeModal: 'subtitles' });
  for (const expected of [['hex'], ['context'], ['overflow'], ['color'], ['modal', 'focus'], ['back']]) {
    f.calls.length = 0; f.linux(); assert.deepEqual(f.calls, expected);
  }
});
for (const modal of ['sources', 'episodes', 'audio', 'subtitles', 'speed', 'videoSettings']) {
  test(`${modal} closes before a toast or error and never emits Back`, () => {
    const f = fixture({ activeModal: modal, error: 'failed', state: { skipSubmitToastDismissible: true } });
    f.linux(); assert.deepEqual(f.calls, ['modal', 'focus']);
    f.calls.length = 0; f.linux(); assert.deepEqual(f.calls, ['skipSubmitDismiss']);
  });
}
test('non-dismissible result toast permits Back', () => {
  const f = fixture({ state: { skipSubmitToastDismissible: false } });
  f.linux(); assert.deepEqual(f.calls, ['back']);
});
test('hero surfaces never route Escape into playback navigation', () => {
  for (const overrides of [{ isHeroTrailerSurface: true }, { state: { heroTrailerMode: true } }]) {
    const f = fixture(overrides); f.linux(); f.focused(); assert.deepEqual(f.calls, []);
  }
});
test('focused Windows/macOS handler retains its original error-before-modal order', () => {
  const f = fixture({ activeModal: 'sources', error: 'failed' });
  f.focused(); assert.deepEqual(f.calls, ['prevent', 'back']);
});
test('focused handler retains panel ordering and shared toast Back behavior', () => {
  const f = fixture({ activeModal: 'audio', state: { skipSubmitToastDismissible: true } });
  f.focused(); assert.deepEqual(f.calls, ['prevent', 'modal', 'focus']);
  f.calls.length = 0; f.focused(); assert.deepEqual(f.calls, ['prevent', 'back']);
});
