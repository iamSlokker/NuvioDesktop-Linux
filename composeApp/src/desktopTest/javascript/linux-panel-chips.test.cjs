// Execute the real filter-chip navigation block from controls.js in a vm sandbox, with fake chip rows
// and counters for the list re-renders it triggers.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');

const start = source.indexOf('// >>> linux-panel-chips');
const end = source.indexOf('// <<< linux-panel-chips');
assert.ok(start >= 0 && end > start, 'chip navigation block must exist');
const block = source.slice(start, end);

function chip(selected = false) {
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

// Each list holds its own chips. `replaceSource` simulates a state push that rebuilds the chips.
function load({ sourceChips = [chip(false), chip(true), chip(false)], episodeChips = [] } = {}) {
  const lists = { source: sourceChips, episode: episodeChips };
  const counts = { sourceRows: 0, episodeStreams: 0 };
  const ctx = {
    sourceFilterList: { querySelectorAll: () => lists.source },
    episodeStreamFilterList: { querySelectorAll: () => lists.episode },
    renderSourceVirtualRows: () => { counts.sourceRows += 1; },
    renderEpisodeStreams: () => { counts.episodeStreams += 1; },
  };
  vm.createContext(ctx);
  vm.runInContext(block + '\nglobalThis.__chips = { enterChipRow, handleChipKey, applyChipFocus, leaveChipRow, focus: () => keyboardChipFocus, index: () => keyboardChipIndex };', ctx);
  return { ctx, lists, counts, chips: ctx.__chips };
}

const highlighted = list => list.findIndex(c => c.has('keyboard-focused'));

test('Up from the top row enters the chip row at the selected chip', () => {
  const { lists, chips, counts } = load();
  assert.equal(chips.enterChipRow('sources'), true);
  assert.equal(chips.focus(), 'sources');
  assert.equal(highlighted(lists.source), 1);
  assert.equal(counts.sourceRows, 1, 'the list rows re-render to hold their highlight off');
});

test('with no chips the chip row cannot be entered', () => {
  const { chips } = load({ sourceChips: [] });
  assert.equal(chips.enterChipRow('sources'), false);
  assert.equal(chips.focus(), '');
});

test('Left and Right move between chips and stop at the ends', () => {
  const { lists, chips } = load();
  chips.enterChipRow('sources');               // index 1
  chips.handleChipKey('ArrowRight');           // 2
  chips.handleChipKey('ArrowRight');           // clamped
  assert.equal(highlighted(lists.source), 2);
  chips.handleChipKey('ArrowLeft');
  chips.handleChipKey('ArrowLeft');
  chips.handleChipKey('ArrowLeft');            // clamped at 0
  assert.equal(highlighted(lists.source), 0);
});

test('Enter clicks the highlighted chip and no other', () => {
  const { lists, chips } = load();
  chips.enterChipRow('sources');
  chips.handleChipKey('ArrowLeft');            // 0
  chips.handleChipKey('Enter');
  assert.equal(lists.source[0].clicks, 1);
  assert.equal(lists.source[1].clicks, 0);
  assert.equal(lists.source[2].clicks, 0);
});

test('Down leaves the chip row and re-renders the list rows', () => {
  const { lists, chips, counts } = load();
  chips.enterChipRow('sources');
  const before = counts.sourceRows;
  chips.handleChipKey('ArrowDown');
  assert.equal(chips.focus(), '');
  assert.equal(highlighted(lists.source), -1);
  assert.equal(counts.sourceRows, before + 1);
});

test('the chip highlight survives a state push that rebuilds the chips', () => {
  const { lists, chips, ctx } = load();
  chips.enterChipRow('sources');
  chips.handleChipKey('ArrowRight');           // 2
  const rebuilt = [chip(false), chip(false), chip(true)];
  lists.source = rebuilt;
  chips.applyChipFocus();
  assert.equal(highlighted(rebuilt), 2);
  assert.equal(ctx.sourceFilterList.querySelectorAll().length, 3);
});

test('episode-stream chips use their own list and re-render the streams', () => {
  const { lists, chips, counts } = load({ episodeChips: [chip(false), chip(true)] });
  assert.equal(chips.enterChipRow('episodeStreams'), true);
  assert.equal(highlighted(lists.episode), 1);
  assert.equal(counts.episodeStreams, 1);
  assert.equal(counts.sourceRows, 0, 'the sources list is not touched');
  chips.handleChipKey('ArrowDown');
  assert.equal(counts.episodeStreams, 2);
});

test('the sources panel is wired to the chip row', () => {
  const router = source.slice(source.indexOf('window.nuvioHandleKeyboardPanelKey'), source.indexOf('\n};', source.indexOf('window.nuvioHandleKeyboardPanelKey')));
  assert.match(router, /if \(keyboardChipFocus === "sources"\) \{\s*handleChipKey\(code\);/);
  assert.match(router, /if \(code === "ArrowUp" && keyboardSourceIndex === 0 && enterChipRow\("sources"\)\) return;/);
  const renderSources = source.slice(source.indexOf('const renderSourceModal'), source.indexOf('\n};', source.indexOf('const renderSourceModal')));
  assert.match(renderSources, /applyChipFocus\(\);/);
  assert.match(source, /index === keyboardSourceIndex && keyboardChipFocus !== "sources"\);/);
  assert.match(source, /keyboardPanelMode = "sources";\s*keyboardChipFocus = "";/);
});

test('the episode-stream panel is wired to the chip row', () => {
  const router = source.slice(source.indexOf('window.nuvioHandleKeyboardPanelKey'), source.indexOf('\n};', source.indexOf('window.nuvioHandleKeyboardPanelKey')));
  assert.match(router, /if \(keyboardChipFocus === "episodeStreams"\) \{\s*handleChipKey\(code\);/);
  assert.match(router, /if \(code === "ArrowUp" && keyboardEpisodeStreamIndex === 0 && enterChipRow\("episodeStreams"\)\) return;/);
  const renderStreams = source.slice(source.indexOf('const renderEpisodeStreams'), source.indexOf('\n};', source.indexOf('const renderEpisodeStreams')));
  assert.match(renderStreams, /applyChipFocus\(\);/);
  assert.match(source, /keyboardEpisodeStreamIndex && keyboardChipFocus !== "episodeStreams"\);/);
  assert.match(source, /keyboardPanelMode = "episodes";\s*keyboardChipFocus = "";/);
});
