// Execute the real font dropdown from controls.js in a vm sandbox, over a small fake DOM. The dropdown
// stands in for the native font <select>: Enter or a click opens its list, arrows move the highlight,
// Enter or a click chooses, and the choice fires the select's change event.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');

const styleBlock = source.slice(source.indexOf('// >>> linux-style-panel-nav'), source.indexOf('// <<< linux-style-panel-nav'));
const trackBlock = source.slice(source.indexOf('// >>> linux-subtitle-panel-nav'), source.indexOf('// <<< linux-subtitle-panel-nav'));
const chipsBlock = source.slice(source.indexOf('// >>> linux-panel-chips'), source.indexOf('// <<< linux-panel-chips'));
assert.ok(styleBlock.length > 0 && trackBlock.length > 0 && chipsBlock.length > 0);

function fakeNode(tag) {
  const classes = new Set();
  const node = {
    tagName: tag.toUpperCase(),
    className: '',
    textContent: '',
    hidden: false,
    disabled: false,
    children: [],
    attrs: {},
    listeners: {},
    clicks: 0,
    changes: 0,
    scrolls: 0,
    selectedIndex: 0,
    classList: {
      add: c => classes.add(c),
      remove: c => classes.delete(c),
      contains: c => classes.has(c),
      toggle: (c, on) => {
        const value = on === undefined ? !classes.has(c) : on;
        if (value) classes.add(c);
        else classes.delete(c);
        return value;
      },
    },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    appendChild(child) { child.parentNode = this; this.children.push(child); return child; },
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
    dispatch(type, event) { (this.listeners[type] || []).forEach(fn => fn(event)); },
    click() { this.clicks += 1; this.dispatch('click', { stopPropagation() {} }); },
    dispatchEvent(event) { if (event.type === 'change') this.changes += 1; },
    scrollIntoView() { this.scrolls += 1; },
    getClientRects() { return this.hidden ? [] : [{}]; },
    contains(target) { return target === this || this.children.some(c => c.contains(target)); },
  };
  return node;
}

function load({ fonts = ['Default', 'Arial', 'Verdana'], chosen = 1 } = {}) {
  const docListeners = {};
  const container = { children: [], insertBefore(node) { this.children.push(node); node.parentNode = this; } };
  const select = fakeNode('select');
  select.options = fonts.map(f => ({ textContent: f }));
  select.selectedIndex = chosen;
  select.parentNode = container;
  const document = {
    createElement: tag => fakeNode(tag),
    addEventListener: (type, fn) => { docListeners[type] = fn; },
  };
  const sent = [];
  const ctx = {
    document,
    fontFamilySelect: select,
    state: { subtitleActiveTab: 'Style' },
    keyboardPanelMode: 'subtitles',
    subtitleBuiltInTab: fakeNode('button'),
    subtitleAddonsTab: fakeNode('button'),
    subtitleStyleTab: fakeNode('button'),
    SUBTITLE_TABS: ['BuiltIn', 'Addons', 'Style'],
    selectSubtitleTab: () => {},
    Event: function Event(type) { this.type = type; },
    send: (type, value) => sent.push([type, value]),
  };
  vm.createContext(ctx);
  vm.runInContext(styleBlock + trackBlock + chipsBlock +
    '\nglobalThis.__dd = { handleStylePanelKey, focused: () => keyboardStyleEl, open: () => styleDropdownOpen, close: closeStyleDropdown, index: () => styleDropdownIndex, menu: () => styleDropdownMenu, trigger: () => styleDropdownTrigger, label: () => styleDropdownLabel };', ctx);
  const wrap = container.children[0];
  const trigger = wrap.children[0];
  const menu = wrap.children[1];
  const row = {
    querySelectorAll: () => [trigger, ...menu.children],
  };
  ctx.subtitleStylePanel = { querySelectorAll: () => [row] };
  return { ctx, select, trigger, menu, docListeners, sent, dd: ctx.__dd };
}

const highlightedItem = menu => menu.children.findIndex(item => item.classList.contains('keyboard-focused'));

test('the trigger shows the current font', () => {
  const { trigger } = load({ chosen: 1 });
  assert.equal(trigger.children[0].textContent, 'Arial');
  assert.equal(trigger.attrs['aria-expanded'], 'false');
});

test('Enter on the focused trigger opens the list with the current font highlighted', () => {
  const { trigger, menu, dd, select } = load({ chosen: 1 });
  dd.handleStylePanelKey('ArrowDown');           // focus the trigger, the only control
  assert.equal(dd.focused(), trigger);
  dd.handleStylePanelKey('Enter');               // Enter clicks the trigger
  assert.equal(menu.hidden, false);
  assert.equal(menu.children.length, 3);
  assert.equal(highlightedItem(menu), 1);
  assert.equal(trigger.attrs['aria-expanded'], 'true');
  assert.equal(select.changes, 0, 'opening chooses nothing');
});

test('Up and Down move the highlight inside the open list, and stop at the ends', () => {
  const { menu, dd, trigger } = load({ chosen: 0 });
  dd.handleStylePanelKey('ArrowDown');
  dd.handleStylePanelKey('Enter');
  dd.handleStylePanelKey('ArrowDown');
  dd.handleStylePanelKey('ArrowDown');
  dd.handleStylePanelKey('ArrowDown');           // clamped at the last font
  assert.equal(highlightedItem(menu), 2);
  dd.handleStylePanelKey('ArrowUp');
  assert.equal(highlightedItem(menu), 1);
  assert.equal(dd.focused(), trigger, 'the arrows move inside the list, not the rows');
});

test('Enter chooses the highlighted font, fires change, closes the list and updates the label', () => {
  const { menu, dd, select, trigger } = load({ chosen: 0 });
  dd.handleStylePanelKey('ArrowDown');
  dd.handleStylePanelKey('Enter');
  dd.handleStylePanelKey('ArrowDown');
  dd.handleStylePanelKey('Enter');
  assert.equal(select.selectedIndex, 1);
  assert.equal(select.changes, 1);
  assert.equal(menu.hidden, true);
  assert.equal(trigger.children[0].textContent, 'Arial');
});

test('a click on the trigger opens the list, and a click on a font chooses it', () => {
  const { menu, select, trigger } = load({ chosen: 1 });
  trigger.click();
  assert.equal(menu.hidden, false);
  menu.children[2].click();
  assert.equal(select.selectedIndex, 2);
  assert.equal(select.changes, 1);
  assert.equal(menu.hidden, true);
});

test('a click on the trigger while open closes the list without choosing', () => {
  const { menu, select, trigger } = load({ chosen: 1 });
  trigger.click();
  trigger.click();
  assert.equal(menu.hidden, true);
  assert.equal(select.changes, 0);
});

test('a press outside the dropdown closes an open list', () => {
  const { menu, docListeners, trigger } = load({ chosen: 1 });
  trigger.click();
  docListeners.pointerdown({ target: fakeNode('div') });
  assert.equal(menu.hidden, true);
});

test('the list keeps the arrow keys from reaching the rows while it is open', () => {
  const { menu, dd, trigger } = load({ chosen: 0 });
  dd.handleStylePanelKey('ArrowDown');
  dd.handleStylePanelKey('Enter');
  dd.handleStylePanelKey('ArrowRight');          // closes the list, as the arrows do in a native popup
  assert.equal(menu.hidden, true);
  assert.equal(dd.focused(), trigger);
});

test('the wiring reaches Escape, the panel render and the menu styles', () => {
  const { } = load();
  const escape = source.slice(source.indexOf('function handlePlayerEscape'), source.indexOf('\n}', source.indexOf('function handlePlayerEscape')));
  assert.match(escape, /if \(styleDropdownOpen\) \{ closeStyleDropdown\(\); return; \}/);
  const render = source.slice(source.indexOf('const renderSubtitleStylePanel'), source.indexOf('\n};', source.indexOf('const renderSubtitleStylePanel')));
  assert.match(render, /syncStyleDropdownLabel\(\);/);
  const css = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.css'), 'utf8');
  assert.match(css, /\.style-dropdown-menu\[hidden\] \{ display: none; \}/);
});
