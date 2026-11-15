// Volume slider focus on Linux: Enter selects the slider into a four-arrow volume mode (nav state 4,
// LinuxHudNavState.VOLUME_FOCUSED) in which Up/Right raise and Down/Left lower the volume while the
// slider keeps focus, and Escape leaves that mode. Merely focused it is an ordinary control, so
// Left/Right move between controls. The "Player volume control" setting (volume-control-hidden)
// takes the slider out of the nav order. Runs the real linux-hud-nav block and chrome timer from
// controls.js in a vm sandbox, with a fake clock and fake controls; no browser/window.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const controlsJsPath = path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js');
const source = fs.readFileSync(controlsJsPath, 'utf8');
const start = source.indexOf('// >>> linux-hud-nav');
const end = source.indexOf('// <<< linux-hud-nav');
assert.ok(start >= 0 && end > start, 'linux-hud-nav block must exist');
const block = source.slice(start, end);

const timerStart = source.indexOf('const isOpeningOverlayActive');
const timerEnd = source.indexOf('const updateChromePointerInside');
assert.ok(timerStart >= 0 && timerEnd > timerStart, 'chrome auto-hide timer must exist');
const timer = source.slice(timerStart, timerEnd);

// Same inputs and reduced helpers as linux-hud-dpad-nav.test.cjs. adjustLocalVolume is the one
// stub: it keeps the real clamp (0..200) and skips the pill and DOM sync, which need a browser.
const TAIL = `
let chromeAutoHideTimer = 0;
let chromeAutoHideKey = "";
let chromeAutoHideActivity = 0;
let chromeInteractionLastNotedAt = 0;
const chromeAutoHideDelayMs = 5500;
const chromeActivityThrottleMs = 300;
let isChromePointerDown = false;
let isChromeFocusInside = false;
let isScrubbing = false;
let hasReceivedPlayerControls = true;
const chromeInteractionSelector = "";
const renderChrome = () => { syncChromeAutoHideTimer(false); syncHudNavState(); };
const revealChromeForPlaybackInteraction = () => {
  if (state.controlsVisible) { noteChromeActivity(true); return; }
  chromeAutoHideActivity += 1;
  state = { ...state, controlsVisible: true };
  renderChrome();
  send("revealChrome", 0);
};
let localVolume = 100;
const adjustLocalVolume = deltaPercent => {
  localVolume = Math.max(0, Math.min(200, localVolume + deltaPercent));
};
// A top-level let is not a property of the vm context, so expose the volume through accessors.
globalThis.__volume = { get: () => localVolume, set: v => { localVolume = v; } };
globalThis.__hostHide = () => { state = { ...state, controlsVisible: false }; syncHudNavState(); };
globalThis.__nav = {
  handleHudNavKey, hudNavStateValue, syncHudNavState, hideHudForNav,
  reveal: () => revealChromeForPlaybackInteraction(),
  focused: () => navFocusEl,
};
`;

// `hidden` models what the CSS does: display:none gives no client rects, so navFocusable drops it.
function el(id, left, top, { width = 40, height = 40, hidden = false } = {}) {
  const classes = new Set();
  return {
    id,
    hidden,
    disabled: false,
    classList: { add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c) },
    getBoundingClientRect: () => ({ left, top, width, height }),
    getClientRects() { return this.hidden ? [] : [{}]; },
    focus() {},
    clicks: 0,
    click() { this.clicks += 1; },
  };
}

// Row 1: seek bar. Row 2: transport, mute button, volume slider (the last item, at the right end).
const layout = ({ volumeHidden = false } = {}) => [
  el('seek', 0, 50, { width: 300, height: 20 }),
  el('prev', 0, 100), el('toggle', 50, 100), el('next', 100, 100),
  el('volumeMuteButton', 150, 100, { hidden: volumeHidden }),
  el('playerVolumeSlider', 200, 100, { width: 80, hidden: volumeHidden }),
];

function fakeClock() {
  let now = 0;
  let nextId = 1;
  const timers = [];
  return {
    now: () => now,
    setTimeout(fn, ms) {
      const id = nextId;
      nextId += 1;
      timers.push({ id, fn, due: now + ms });
      return id;
    },
    clearTimeout(id) {
      const index = timers.findIndex(timer => timer.id === id);
      if (index >= 0) timers.splice(index, 1);
    },
    advance(ms) {
      const target = now + ms;
      for (;;) {
        let next = -1;
        timers.forEach((timer, index) => {
          if (timer.due <= target && (next < 0 || timer.due < timers[next].due)) next = index;
        });
        if (next < 0) break;
        const [due] = timers.splice(next, 1);
        now = due.due;
        due.fn();
      }
      now = target;
    },
  };
}

function load(options = {}) {
  const els = layout(options);
  const sent = [];
  const clock = fakeClock();
  const ctx = {
    linuxHost: true,
    isHeroTrailerSurface: false,
    state: { controlsVisible: true, isLocked: false, heroTrailerMode: false, isPlaying: true },
    activeModal: '', contextMenuOpen: false, colorGradePanelOpen: false, actionOverflowOpen: false,
    subtitleHexField: '', keyboardPanelMode: '',
    window: { performance: { now: clock.now }, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout },
    document: { activeElement: null },
    root: { querySelectorAll: () => els, querySelector: sel => els.find(e => `#${e.id}` === sel) || null },
    seek: els.find(e => e.id === 'seek'),
    playerVolumeSlider: els.find(e => e.id === 'playerVolumeSlider'),
    playbackErrorText: () => '',
    send: (type, value) => sent.push([type, value]),
  };
  vm.createContext(ctx);
  vm.runInContext(block + timer + TAIL, ctx);
  const byId = id => els.find(e => e.id === id);
  return { ctx, sent, nav: ctx.__nav, byId, els };
}

const focusedId = nav => (nav.focused() ? nav.focused().id : null);
const sentOf = (sent, type) => sent.filter(([t]) => t === type);

// Reaching the slider is ordinary Left/Right navigation; the volume mode itself is entered with Enter.
const focusVolumeSlider = nav => {
  while (focusedId(nav) !== 'playerVolumeSlider') nav.handleHudNavKey('ArrowRight');
};

test('Enter on the focused volume slider selects it into the volume mode', () => {
  const { ctx, nav } = load();
  focusVolumeSlider(nav);
  assert.equal(ctx.__nav.hudNavStateValue(), 3);
  nav.handleHudNavKey('Enter');
  // 4 is LinuxHudNavState.VOLUME_FOCUSED: Kotlin forwards the arrows and Escape in this state only.
  assert.equal(ctx.__nav.hudNavStateValue(), 4);
  assert.equal(focusedId(nav), 'playerVolumeSlider');
});

test('selected, Up raises volume one step and keeps focus on the slider', () => {
  const { ctx, sent, nav } = load();
  focusVolumeSlider(nav);
  nav.handleHudNavKey('Enter');
  sent.length = 0;
  nav.handleHudNavKey('ArrowUp');
  assert.equal(ctx.__volume.get(), 105);
  assert.equal(focusedId(nav), 'playerVolumeSlider');
  assert.deepEqual(sentOf(sent, 'volumeDelta'), [['volumeDelta', 0.05]]);
  assert.deepEqual(sentOf(sent, 'keyboardSeekForward'), []);
});

test('selected, Down lowers volume one step and keeps focus', () => {
  const { ctx, sent, nav } = load();
  focusVolumeSlider(nav);
  nav.handleHudNavKey('Enter');
  sent.length = 0;
  nav.handleHudNavKey('ArrowDown');
  assert.equal(ctx.__volume.get(), 95);
  assert.equal(focusedId(nav), 'playerVolumeSlider');
  assert.deepEqual(sentOf(sent, 'volumeDelta'), [['volumeDelta', -0.05]]);
});

test('volume clamps at 0 and 200 in the volume mode without leaving the slider', () => {
  const { ctx, nav } = load();
  focusVolumeSlider(nav);
  nav.handleHudNavKey('Enter');
  ctx.__volume.set(0);
  nav.handleHudNavKey('ArrowDown');
  assert.equal(ctx.__volume.get(), 0);
  ctx.__volume.set(200);
  nav.handleHudNavKey('ArrowUp');
  assert.equal(ctx.__volume.get(), 200);
  assert.equal(focusedId(nav), 'playerVolumeSlider');
  assert.equal(ctx.__nav.hudNavStateValue(), 4);
});

test('Escape leaves the volume mode so the arrows navigate again', () => {
  const { ctx, nav } = load();
  focusVolumeSlider(nav);
  nav.handleHudNavKey('Enter');
  const before = ctx.__volume.get();
  nav.handleHudNavKey('Escape');
  assert.equal(ctx.__nav.hudNavStateValue(), 3);
  nav.handleHudNavKey('ArrowUp');     // a plain arrow again: row 1, the seek bar
  assert.equal(focusedId(nav), 'seek');
  assert.equal(ctx.__volume.get(), before);
});

test('Left and Right on the volume slider still move focus between controls', () => {
  const { ctx, nav } = load();
  while (focusedId(nav) !== 'playerVolumeSlider') nav.handleHudNavKey('ArrowRight');
  const before = ctx.__volume.get();
  nav.handleHudNavKey('ArrowLeft');
  assert.equal(focusedId(nav), 'volumeMuteButton');
  assert.equal(ctx.__volume.get(), before);
  nav.handleHudNavKey('ArrowRight');
  assert.equal(focusedId(nav), 'playerVolumeSlider');
  assert.equal(ctx.__volume.get(), before);
});

test('Up and Down on the seek bar still change rows and do not change volume', () => {
  const { ctx, nav } = load();
  nav.handleHudNavKey('ArrowLeft');    // enter at toggle, row 2
  nav.handleHudNavKey('ArrowUp');      // seek bar, row 1
  assert.equal(focusedId(nav), 'seek');
  nav.handleHudNavKey('ArrowDown');    // back down to row 2
  assert.notEqual(focusedId(nav), 'seek');
  assert.equal(ctx.__volume.get(), 100);
});

test('with the volume control hidden the slider is skipped by Left and Right', () => {
  const { nav } = load({ volumeHidden: true });
  nav.handleHudNavKey('ArrowLeft');    // enter at toggle
  nav.handleHudNavKey('ArrowRight');   // next
  nav.handleHudNavKey('ArrowRight');   // no slider: stays at next
  assert.equal(focusedId(nav), 'next');
});

test('hiding the slider while it holds focus drops nav focus on the next sync', () => {
  const { els, nav } = load();
  while (focusedId(nav) !== 'playerVolumeSlider') nav.handleHudNavKey('ArrowRight');
  // The setting turns off: CSS removes the slider, then the next render syncs nav state.
  els.find(e => e.id === 'playerVolumeSlider').hidden = true;
  els.find(e => e.id === 'volumeMuteButton').hidden = true;
  nav.syncHudNavState();
  assert.equal(focusedId(nav), null);
});

// The two halves that cannot run here: the render toggle and the CSS rule. Checked against the
// source, so a rename on either side fails the test.
test('controls.js toggles volume-control-hidden from the setting, and controls.css hides the wrapper', () => {
  assert.match(source, /state\.volumeControlEnabled === false\)/);
  assert.match(source, /root\.classList\.toggle\("volume-control-hidden"/);
  const css = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.css'), 'utf8');
  assert.match(css, /\.player\.volume-control-hidden #playerVolumeControl\s*\{\s*display:\s*none;/);
});

test('the keydown fallback sends no volume command for Up or Down when the setting is off', () => {
  // shortcutCommandForEvent is not sliced here; check its arrow branches in the source.
  assert.match(source, /case "ArrowUp":\s*return state\.volumeControlEnabled === false \? "" : "volumeUp";/);
  assert.match(source, /case "ArrowDown":\s*return state\.volumeControlEnabled === false \? "" : "volumeDown";/);
});
