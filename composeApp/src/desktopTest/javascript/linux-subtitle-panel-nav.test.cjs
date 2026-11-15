// Execute the real track-panel navigation block from controls.js (subtitles and audio) in a vm sandbox,
// plus checks that the wiring into openPlayerModal, the renders and the panel-key router is present.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');

const start = source.indexOf('// >>> linux-subtitle-panel-nav');
const end = source.indexOf('// <<< linux-subtitle-panel-nav');
assert.ok(start >= 0 && end > start, 'track panel navigation block must exist');
// The Style-tab block is part of the same script in controls.js, so the sandbox loads it too.
const styleStart = source.indexOf('// >>> linux-style-panel-nav');
const styleEnd = source.indexOf('// <<< linux-style-panel-nav');
assert.ok(styleStart >= 0 && styleEnd > styleStart, 'Style-tab navigation block must exist');
const block = source.slice(start, end) + source.slice(styleStart, styleEnd);

function row(selected = false) {
  const classes = new Set(selected ? ['selected'] : []);
  return {
    classList: {
      add: c => classes.add(c),
      remove: c => classes.delete(c),
      contains: c => classes.has(c),
      toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)),
    },
    clicks: 0,
    click() { this.clicks += 1; },
    scrollIntoView() {},
    has: c => classes.has(c),
  };
}

// keyboardPanelMode 'subtitles' or 'audio'. Subtitle rows come from whichever tab is active.
function load({ rows = [row(false), row(true), row(false)], tab = 'BuiltIn', mode = 'subtitles' } = {}) {
  const sent = [];
  const tabCalls = [];
  const builtIn = { querySelectorAll: () => (tab === 'BuiltIn' ? rows : []) };
  const addons = { querySelectorAll: () => (tab === 'Addons' ? rows : []) };
  const audio = { querySelectorAll: () => rows };
  const ctx = {
    state: { subtitleActiveTab: tab },
    fontFamilySelect: null,
    keyboardPanelMode: mode,
    subtitleTrackList: builtIn,
    addonSubtitleList: addons,
    audioTrackList: audio,
    subtitleBuiltInTab: { classList: { add() {}, remove() {}, contains: () => false } },
    subtitleAddonsTab: { classList: { add() {}, remove() {}, contains: () => false } },
    subtitleStyleTab: { classList: { add() {}, remove() {}, contains: () => false } },
    subtitleStylePanel: { querySelectorAll: () => [] },
    SUBTITLE_TABS: ['BuiltIn', 'Addons', 'Style'],
    selectSubtitleTab: (name, index) => {
      tabCalls.push([name, index]);
      ctx.state = { ...ctx.state, subtitleActiveTab: name };
    },
    send: (type, value) => sent.push([type, value]),
  };
  vm.createContext(ctx);
  vm.runInContext(block + '\nglobalThis.__sub = { handleTrackPanelKey, applyTrackKeyboardFocus, enterTrackKeyboardMode, currentTrackRows, index: () => keyboardTrackIndex };', ctx);
  return { ctx, sent, tabCalls, rows, sub: ctx.__sub };
}

const highlighted = rows => rows.findIndex(r => r.has('keyboard-focused'));

test('the initial highlight is the selected row', () => {
  const { rows, sub } = load();
  sub.applyTrackKeyboardFocus();
  assert.equal(highlighted(rows), 1);
});

test('Down and Up move the highlight and clamp at both ends', () => {
  const { rows, sub } = load();
  sub.handleTrackPanelKey('ArrowDown');        // selected row 1 -> 2
  assert.equal(highlighted(rows), 2);
  sub.handleTrackPanelKey('ArrowDown');        // clamped at the last row
  assert.equal(highlighted(rows), 2);
  sub.handleTrackPanelKey('ArrowUp');
  sub.handleTrackPanelKey('ArrowUp');
  sub.handleTrackPanelKey('ArrowUp');          // clamped at the first row
  assert.equal(highlighted(rows), 0);
});

test('Enter clicks the highlighted row and nothing else', () => {
  const { rows, sub } = load();
  sub.handleTrackPanelKey('ArrowDown');        // row 2
  sub.handleTrackPanelKey('Enter');
  assert.equal(rows[2].clicks, 1);
  assert.equal(rows[1].clicks, 0);
  assert.equal(rows[0].clicks, 0);
});

test('Left and Right switch subtitle tabs, and stop at the ends', () => {
  const { tabCalls, sub, ctx } = load();
  sub.handleTrackPanelKey('ArrowRight');
  assert.deepEqual(tabCalls, [['Addons', 1]]);
  sub.handleTrackPanelKey('ArrowRight');
  assert.deepEqual(tabCalls, [['Addons', 1], ['Style', 2]]);
  sub.handleTrackPanelKey('ArrowRight');       // already on the last tab
  assert.equal(tabCalls.length, 2);
  assert.equal(ctx.state.subtitleActiveTab, 'Style');
});

test('the Style tab has no rows, so Up, Down and Enter do nothing', () => {
  const { sent, sub } = load({ tab: 'Style' });
  sub.handleTrackPanelKey('ArrowDown');
  sub.handleTrackPanelKey('Enter');
  assert.deepEqual(sent, []);
  assert.equal(sub.index(), -1);
});

test('the highlight is re-applied after a rebuild, because the rows are replaced on every push', () => {
  const first = load();
  first.sub.handleTrackPanelKey('ArrowDown');  // row 2 highlighted
  const rebuilt = [row(false), row(false), row(false)];
  first.ctx.subtitleTrackList = { querySelectorAll: () => rebuilt };
  first.sub.applyTrackKeyboardFocus();
  assert.equal(highlighted(rebuilt), 2);
});

test('Addons rows are navigated the same way', () => {
  const { rows, sub } = load({ tab: 'Addons' });
  sub.applyTrackKeyboardFocus();
  assert.equal(highlighted(rows), 1);
  sub.handleTrackPanelKey('Enter');
  assert.equal(rows[1].clicks, 1);
});

test('the Addons fetch row is reachable and Enter fetches', () => {
  const fetch = row(false);
  const { rows, sub, sent } = load({ tab: 'Addons', rows: [fetch] });
  sub.applyTrackKeyboardFocus();
  assert.equal(highlighted(rows), 0);
  sub.handleTrackPanelKey('Enter');
  assert.equal(fetch.clicks, 1);
  assert.deepEqual(sent, []);                  // the click's own handler does the sending
});

test('audio rows: the selected row starts highlighted, Up and Down move, Enter clicks', () => {
  const { rows, sub } = load({ mode: 'audio' });
  sub.applyTrackKeyboardFocus();
  assert.equal(highlighted(rows), 1);
  sub.handleTrackPanelKey('ArrowDown');
  assert.equal(highlighted(rows), 2);
  sub.handleTrackPanelKey('Enter');
  assert.equal(rows[2].clicks, 1);
});

test('audio ignores Left and Right, which have no tabs to switch', () => {
  const { rows, sub, tabCalls } = load({ mode: 'audio' });
  sub.handleTrackPanelKey('ArrowLeft');
  sub.handleTrackPanelKey('ArrowRight');
  assert.deepEqual(tabCalls, []);
  assert.equal(highlighted(rows), 1);
});

test('keys do nothing when the panel is not in keyboard-panel mode', () => {
  const { sent, sub } = load({ mode: '' });
  sub.handleTrackPanelKey('ArrowDown');
  assert.equal(sub.index(), -1);
  assert.deepEqual(sent, []);
});

test('entering either mode announces it to Kotlin, which then forwards the arrows', () => {
  const sub1 = load({ mode: '' });
  sub1.sub.enterTrackKeyboardMode('subtitles');
  assert.equal(sub1.ctx.keyboardPanelMode, 'subtitles');
  assert.deepEqual(sub1.sent, [['keyboardPanelOpened', 0]]);
  assert.equal(highlighted(sub1.rows), 1);

  const sub2 = load({ mode: '' });
  sub2.sub.enterTrackKeyboardMode('audio');
  assert.equal(sub2.ctx.keyboardPanelMode, 'audio');
  assert.equal(highlighted(sub2.rows), 1);
});

test('the wiring into the open path, the renders and the panel-key router is present', () => {
  const openModal = source.slice(source.indexOf('const openPlayerModal'), source.indexOf('\n};', source.indexOf('const openPlayerModal')));
  assert.match(openModal, /if \(linuxHost && \(modal === "subtitles" \|\| modal === "audio"\)\) enterTrackKeyboardMode\(modal\);/);
  const renderModal = source.slice(source.indexOf('const renderSubtitleModal'), source.indexOf('\n};', source.indexOf('const renderSubtitleModal')));
  assert.match(renderModal, /applyTrackKeyboardFocus\(\);/);
  const renderAudio = source.slice(source.indexOf('const renderAudioTrackList'), source.indexOf('\n};', source.indexOf('const renderAudioTrackList')));
  assert.match(renderAudio, /applyTrackKeyboardFocus\(\);/);
  assert.match(source, /if \(keyboardPanelMode === "subtitles" \|\| keyboardPanelMode === "audio"\) \{\s*handleTrackPanelKey\(code\);/);
  assert.match(source, /row\.className = "track-row addon-fetch-row";/);
});
