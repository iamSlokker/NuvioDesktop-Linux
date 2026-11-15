// Execute the real subtitles Style-tab navigation block from controls.js in a vm sandbox, with fake tab
// buttons and style rows, plus checks that it is wired into the track-panel router and the open path.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');

const start = source.indexOf('// >>> linux-style-panel-nav');
const end = source.indexOf('// <<< linux-style-panel-nav');
assert.ok(start >= 0 && end > start, 'Style-tab navigation block must exist');
const block = source.slice(start, end);

function control(tagName = 'BUTTON', options = 0) {
  const classes = new Set();
  return {
    tagName,
    options: Array.from({ length: options }, () => ({})),
    selectedIndex: 0,
    changes: 0,
    dispatchEvent(event) { if (event.type === 'change') this.changes += 1; },
    scrolls: 0,
    scrollIntoView() { this.scrolls += 1; },
    classList: { add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c) },
    clicks: 0,
    click() { this.clicks += 1; },
    disabled: false,
    hidden: false,
    getClientRects: () => [{}],
    has: c => classes.has(c),
  };
}
const makeTab = () => control();

// Each row is a list of controls; `rowsSpec` lists how many controls each row holds.
function load({ rowsSpec = [2, 3, 1], tab: activeTab = 'Style' } = {}) {
  const rows = rowsSpec.map(n => Array.from({ length: n }, () => control()));
  const tabs = [makeTab(), makeTab(), makeTab()];
  const tabCalls = [];
  const ctx = {
    state: { subtitleActiveTab: activeTab },
    fontFamilySelect: null,
    keyboardPanelMode: 'subtitles',
    subtitleBuiltInTab: tabs[0],
    subtitleAddonsTab: tabs[1],
    subtitleStyleTab: tabs[2],
    subtitleStylePanel: {
      querySelectorAll: () => rows.map(items => ({ querySelectorAll: () => items })),
    },
    SUBTITLE_TABS: ['BuiltIn', 'Addons', 'Style'],
    Event: function Event(type) { this.type = type; },
    selectSubtitleTab: (name, index) => {
      tabCalls.push([name, index]);
      ctx.state = { ...ctx.state, subtitleActiveTab: name };
    },
  };
  vm.createContext(ctx);
  vm.runInContext(block + '\nglobalThis.__style = { handleStylePanelKey, setStyleFocus, focused: () => keyboardStyleEl, onTabs: () => keyboardStyleOnTabs };', ctx);
  return { ctx, rows, tabs, tabCalls, style: ctx.__style };
}

const at = (rows, r, c) => rows[r][c];

test('Up from the top row of controls enters the tab bar at the active tab', () => {
  const { tabs, style } = load();
  style.handleStylePanelKey('ArrowDown');       // first control (row 0, col 0)
  style.handleStylePanelKey('ArrowUp');
  assert.equal(style.focused(), tabs[2]);       // the Style tab is active
  assert.equal(style.onTabs(), true);
});

test('Left and Right on the tab bar switch tabs and leave the Style tab', () => {
  const { tabs, tabCalls, style } = load();
  style.handleStylePanelKey('ArrowDown');
  style.handleStylePanelKey('ArrowUp');         // on the Style tab button
  style.handleStylePanelKey('ArrowLeft');       // to Addons
  assert.deepEqual(tabCalls, [['Addons', 1]]);
  assert.equal(style.focused(), null, 'focus leaves the Style controls for the track panel');
  assert.equal(tabs.length, 3);
});

test('Down from the tab bar returns to the first control', () => {
  const { rows, style } = load();
  style.handleStylePanelKey('ArrowDown');
  style.handleStylePanelKey('ArrowUp');
  style.handleStylePanelKey('ArrowDown');
  assert.equal(style.focused(), at(rows, 0, 0));
});

test('Left and Right move along a row and stop at its ends', () => {
  const { rows, style } = load();
  style.handleStylePanelKey('ArrowDown');       // row 0, col 0
  style.handleStylePanelKey('ArrowRight');      // row 0, col 1
  assert.equal(style.focused(), at(rows, 0, 1));
  style.handleStylePanelKey('ArrowRight');      // clamped at the row end
  assert.equal(style.focused(), at(rows, 0, 1));
  style.handleStylePanelKey('ArrowLeft');
  style.handleStylePanelKey('ArrowLeft');       // clamped at the row start
  assert.equal(style.focused(), at(rows, 0, 0));
});

test('Up and Down move between rows, keeping the nearest column', () => {
  const { rows, style } = load();
  style.handleStylePanelKey('ArrowDown');
  style.handleStylePanelKey('ArrowRight');      // row 0, col 1
  style.handleStylePanelKey('ArrowDown');       // row 1 has 3 controls: col 1
  assert.equal(style.focused(), at(rows, 1, 1));
  style.handleStylePanelKey('ArrowDown');       // row 2 has 1 control: clamped to col 0
  assert.equal(style.focused(), at(rows, 2, 0));
  style.handleStylePanelKey('ArrowDown');       // last row, stays
  assert.equal(style.focused(), at(rows, 2, 0));
});

test('Enter clicks the focused control and nothing else', () => {
  const { rows, style } = load();
  style.handleStylePanelKey('ArrowDown');
  style.handleStylePanelKey('ArrowRight');
  style.handleStylePanelKey('Enter');
  assert.equal(at(rows, 0, 1).clicks, 1);
  assert.equal(at(rows, 0, 0).clicks, 0);
});

test('the first key without a highlight only places it; Enter does not click', () => {
  const { rows, style } = load();
  style.handleStylePanelKey('Enter');
  assert.equal(style.focused(), at(rows, 0, 0));
  assert.equal(at(rows, 0, 0).clicks, 0);
});

test('a control that is hidden or disabled is skipped', () => {
  const { rows, style } = load({ rowsSpec: [3] });
  rows[0][1].hidden = true;
  style.handleStylePanelKey('ArrowDown');
  style.handleStylePanelKey('ArrowRight');
  assert.equal(style.focused(), at(rows, 0, 2));
});

test('the router sends Style-tab keys to this block, and entering the mode clears a stale highlight', () => {
  const router = source.slice(source.indexOf('const handleTrackPanelKey'), source.indexOf('\n};', source.indexOf('const handleTrackPanelKey')));
  assert.match(router, /if \(keyboardPanelMode === "subtitles" && state\.subtitleActiveTab === "Style"\) \{\s*handleStylePanelKey\(code\);/);
  const enter = source.slice(source.indexOf('const enterTrackKeyboardMode'), source.indexOf('\n};', source.indexOf('const enterTrackKeyboardMode')));
  assert.match(enter, /setStyleFocus\(null\);/);
  const css = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.css'), 'utf8');
  assert.match(css, /#subtitleModal \.style-row \.keyboard-focused/);
});

test('moving focus scrolls the focused control into view', () => {
  const { rows, style } = load();
  style.handleStylePanelKey('ArrowDown');
  style.handleStylePanelKey('ArrowDown');
  assert.equal(at(rows, 1, 0).scrolls, 1);
});


