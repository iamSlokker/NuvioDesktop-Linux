// Execute the real linux-hud-nav block and the real chrome auto-hide timer from controls.js in a vm
// sandbox, with a fake clock and fake controls; no browser/window.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');
const start = source.indexOf('// >>> linux-hud-nav');
const end = source.indexOf('// <<< linux-hud-nav');
assert.ok(start >= 0 && end > start, 'linux-hud-nav block must exist');
const block = source.slice(start, end);

// The chrome auto-hide timer, sliced from the real source: isOpeningOverlayActive through
// noteChromeActivity. Its inputs are declared in TAIL below.
const timerStart = source.indexOf('const isOpeningOverlayActive');
const timerEnd = source.indexOf('const updateChromePointerInside');
assert.ok(timerStart >= 0 && timerEnd > timerStart, 'chrome auto-hide timer must exist');
const timer = source.slice(timerStart, timerEnd);

// Inputs the sliced timer code reads, plus the two chrome functions it calls that live elsewhere in
// controls.js. renderChrome runs the same sync calls the real render does; revealChromeForPlaybackInteraction
// mirrors the real function. Both are the production logic, reduced to what these tests need.
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
globalThis.__hostHide = () => { state = { ...state, controlsVisible: false }; syncHudNavState(); };
globalThis.__nav = {
  handleHudNavKey, hudNavStateValue, syncHudNavState, hideHudForNav,
  reveal: () => revealChromeForPlaybackInteraction(),
  focused: () => navFocusEl,
  scrubbing: () => isScrubbing,
};
`;

function el(id, left, top, { width = 40, height = 40, hidden = false } = {}) {
  const classes = new Set();
  return {
    id,
    hidden,
    disabled: false,
    classList: { add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c) },
    getBoundingClientRect: () => ({ left, top, width, height }),
    getClientRects: () => (hidden ? [] : [{}]),
    focus() {},
    clicks: 0,
    click() { this.clicks += 1; },
  };
}

// Four rows: header, seek bar, transport, actions. volumeMuteButton is hidden and must be skipped.
const layout = () => [
  el('hA', 0, 0), el('hB', 50, 0),
  el('seek', 0, 50, { width: 300, height: 20 }),
  el('prev', 0, 100), el('toggle', 50, 100), el('next', 100, 100),
  el('info', 0, 150), el('resize', 50, 150), el('sources', 100, 150),
  el('volumeMuteButton', 150, 150, { hidden: true }),
];

// A deterministic clock for setTimeout. Timers run only when advance() moves time past them.
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

function load(overrides = {}) {
  const els = layout();
  const sent = [];
  const clock = fakeClock();
  let hostTimer = 0;
  // Models the Kotlin side (PlayerScreenRuntimeEffects): each revealChrome restarts its 5500 ms
  // auto-hide, and when that fires the HUD is hidden. Kotlin is not executed here.
  const hostRestart = () => {
    clock.clearTimeout(hostTimer);
    hostTimer = clock.setTimeout(() => ctx.__hostHide(), 5500);
  };
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
    // The volume slider is not in this layout; the block only reads it for Up/Down focus.
    playerVolumeSlider: null,
    playbackErrorText: () => '',
    send: (type, value) => {
      sent.push([type, value]);
      if (type === 'revealChrome') hostRestart();
    },
    ...overrides,
  };
  vm.createContext(ctx);
  vm.runInContext(block + timer + TAIL, ctx);
  const byId = id => els.find(e => e.id === id);
  return { ctx, sent, nav: ctx.__nav, byId, clock };
}

// A HUD revealed at t=0 by the real reveal path.
function revealed(overrides = {}) {
  const loaded = load({ state: { controlsVisible: false, isLocked: false, heroTrailerMode: false, isPlaying: true }, ...overrides });
  loaded.nav.reveal();
  assert.equal(loaded.ctx.state.controlsVisible, true);
  return loaded;
}

const focusedId = nav => (nav.focused() ? nav.focused().id : null);
// Messages other than the state and reveal bookkeeping, for asserting that a key did nothing else.
const actions = sent => sent.filter(([type]) => type !== 'hudNavState' && type !== 'revealChrome');

test('state reports unavailable, hidden, visible, and focused', () => {
  const { ctx, nav } = load();
  assert.equal(nav.hudNavStateValue(), 2);
  ctx.state.controlsVisible = false;
  assert.equal(nav.hudNavStateValue(), 1);
  ctx.state.controlsVisible = true;
  ctx.activeModal = 'sources';
  assert.equal(nav.hudNavStateValue(), 0);
  ctx.activeModal = '';
  ctx.linuxHost = false;
  assert.equal(nav.hudNavStateValue(), 0);
});

test('an arrow with the HUD visible and no focus enters at the transport button without seeking', () => {
  const { sent, nav, byId } = load();
  nav.handleHudNavKey('ArrowLeft');
  assert.equal(focusedId(nav), 'toggle');
  assert.equal(byId('toggle').classList.contains('chrome-nav-focus'), true);
  assert.equal(nav.hudNavStateValue(), 3);
  assert.deepEqual(actions(sent), []);
});

test('Left and Right move within a row and stop at its ends', () => {
  const { nav } = load();
  nav.handleHudNavKey('ArrowLeft');           // enter at toggle
  nav.handleHudNavKey('ArrowLeft');           // prev
  assert.equal(focusedId(nav), 'prev');
  nav.handleHudNavKey('ArrowLeft');           // stays at the row start
  assert.equal(focusedId(nav), 'prev');
  nav.handleHudNavKey('ArrowRight');          // toggle
  nav.handleHudNavKey('ArrowRight');          // next
  nav.handleHudNavKey('ArrowRight');          // stays at the row end
  assert.equal(focusedId(nav), 'next');
});

test('Up and Down move to the adjacent row, to the control nearest in x, and stop at the first and last rows', () => {
  const { nav } = load();
  nav.handleHudNavKey('ArrowLeft');           // toggle, centre x 70
  nav.handleHudNavKey('ArrowUp');             // seek bar is the row above
  assert.equal(focusedId(nav), 'seek');
  nav.handleHudNavKey('ArrowUp');             // header row; hB (centre 70) is nearest to seek's centre 150
  assert.equal(focusedId(nav), 'hB');
  nav.handleHudNavKey('ArrowUp');             // already the first row
  assert.equal(focusedId(nav), 'hB');
  nav.handleHudNavKey('ArrowDown');           // back to the seek bar
  nav.handleHudNavKey('ArrowDown');           // transport row; next (centre 120) is nearest to 150
  assert.equal(focusedId(nav), 'next');
  nav.handleHudNavKey('ArrowDown');           // action row; sources (centre 120) is nearest to next
  assert.equal(focusedId(nav), 'sources');
  nav.handleHudNavKey('ArrowDown');           // last row
  assert.equal(focusedId(nav), 'sources');
});

test('hidden controls are never focused', () => {
  const { nav } = load();
  nav.handleHudNavKey('ArrowLeft');           // toggle
  nav.handleHudNavKey('ArrowDown');           // resize (centre 70) in the action row
  nav.handleHudNavKey('ArrowRight');          // sources (centre 120)
  nav.handleHudNavKey('ArrowRight');          // hidden volumeMuteButton at x=150 must not be next
  assert.equal(focusedId(nav), 'sources');
});

test('Enter activates the focused control', () => {
  const { sent, nav, byId } = load();
  nav.handleHudNavKey('ArrowLeft');           // enter at toggle without activating it
  assert.equal(byId('toggle').clicks, 0);
  nav.handleHudNavKey('Enter');
  assert.equal(byId('toggle').clicks, 1);
  assert.equal(focusedId(nav), 'toggle');
  assert.deepEqual(actions(sent), []);
});

test('Enter with the HUD hidden reveals it without taking focus', () => {
  const { ctx, sent, nav } = load();
  ctx.state.controlsVisible = false;
  nav.syncHudNavState();
  nav.handleHudNavKey('Enter');
  assert.equal(ctx.state.controlsVisible, true);
  assert.equal(focusedId(nav), null);
  assert.ok(sent.some(([type]) => type === 'revealChrome'));
});

test('arrows with the HUD hidden do nothing here, so Kotlin keeps seeking and volume', () => {
  const { ctx, sent, nav } = load();
  ctx.state.controlsVisible = false;
  nav.handleHudNavKey('ArrowLeft');
  nav.handleHudNavKey('ArrowUp');
  assert.equal(focusedId(nav), null);
  assert.deepEqual(sent, []);
});

test('hiding the HUD clears nav focus and reports the hidden state', () => {
  const { ctx, sent, nav, byId } = load();
  nav.handleHudNavKey('ArrowLeft');
  ctx.state.controlsVisible = false;
  nav.syncHudNavState();
  assert.equal(focusedId(nav), null);
  assert.equal(byId('toggle').classList.contains('chrome-nav-focus'), false);
  assert.deepEqual(sent.at(-1), ['hudNavState', 1]);
});

test('opening a modal clears nav focus and reports unavailable', () => {
  const { ctx, sent, nav } = load();
  nav.handleHudNavKey('ArrowLeft');
  ctx.activeModal = 'sources';
  nav.syncHudNavState();
  assert.equal(focusedId(nav), null);
  assert.deepEqual(sent.at(-1), ['hudNavState', 0]);
});

test('Back hides the HUD and clears focus', () => {
  const { ctx, sent, nav } = load();
  nav.handleHudNavKey('ArrowLeft');
  nav.hideHudForNav();
  assert.equal(ctx.state.controlsVisible, false);
  assert.equal(focusedId(nav), null);
  assert.deepEqual(sent.at(-1), ['hideChrome', 0]);
});

test('the state is only sent when it changes', () => {
  const { nav, sent } = load();
  nav.syncHudNavState();
  nav.syncHudNavState();
  assert.deepEqual(sent.filter(([type]) => type === 'hudNavState'), [['hudNavState', 2]]);
});

test('Windows and macOS never route keys (linuxHost is false)', () => {
  const { nav, sent, ctx } = load({ linuxHost: false });
  nav.handleHudNavKey('ArrowLeft');
  nav.handleHudNavKey('Enter');
  nav.syncHudNavState();
  assert.equal(focusedId(nav), null);
  assert.equal(nav.hudNavStateValue(), 0);
  assert.equal(ctx.state.controlsVisible, true);
  assert.deepEqual(sent, []);
});

// --- Auto-hide while navigating -------------------------------------------------------------

test('the HUD stays up while focus moves, and hides 5.5 s after the last navigation key', () => {
  const { ctx, nav, clock } = revealed();
  clock.advance(4000);
  nav.handleHudNavKey('ArrowRight');          // a focus move 4 s after the reveal
  clock.advance(4000);                        // 8 s after the reveal, 4 s after the move
  assert.equal(ctx.state.controlsVisible, true, 'still visible with focus on a control');
  clock.advance(5600);                        // more than 5.5 s with no input
  assert.equal(ctx.state.controlsVisible, false, 'hidden once idle, with nothing left pinning it');
});

test('Select on a focused control restarts the timer', () => {
  const { ctx, nav, clock } = revealed();
  clock.advance(4000);
  nav.handleHudNavKey('ArrowRight');          // focus toggle at 4 s
  clock.advance(4000);                        // 8 s
  assert.equal(ctx.state.controlsVisible, true, 'the focused HUD is still up at 8 s');
  nav.handleHudNavKey('Enter');               // activate at 8 s, while visible
  clock.advance(4000);                        // 12 s: 4 s after the activation
  assert.equal(ctx.state.controlsVisible, true, 'the activation restarted the 5.5 s window');
});

test('navigation keys at a row end still count as activity', () => {
  const { ctx, nav, clock } = revealed();
  nav.handleHudNavKey('ArrowLeft');           // focus toggle at 0 s
  clock.advance(4000);
  nav.handleHudNavKey('ArrowRight');          // next
  clock.advance(4000);
  nav.handleHudNavKey('ArrowRight');          // already at the row end, so focus stays put
  clock.advance(4000);
  assert.equal(ctx.state.controlsVisible, true, 'the row-end press restarted the window');
});

test('steady navigation keeps the HUD up well past 5.5 s', () => {
  const { ctx, nav, clock } = revealed();
  nav.handleHudNavKey('ArrowLeft');
  for (let step = 0; step < 6; step += 1) {
    clock.advance(3000);
    nav.handleHudNavKey(step % 2 === 0 ? 'ArrowRight' : 'ArrowLeft');
    assert.equal(ctx.state.controlsVisible, true, `visible after step ${step}`);
  }
});

test('a focused control does not keep the HUD up when nothing else happens', () => {
  const { ctx, nav, clock } = revealed();
  nav.handleHudNavKey('ArrowRight');          // focus at t=0, then no input at all
  clock.advance(5400);
  assert.equal(ctx.state.controlsVisible, true, 'still inside the 5.5 s window');
  clock.advance(200);
  assert.equal(ctx.state.controlsVisible, false, 'hidden 5.5 s after the last input');
  assert.equal(nav.focused(), null, 'hiding cleared focus');
});

test('an idle HUD hides 5.5 s after the reveal when no input happens', () => {
  const { ctx, clock } = revealed();
  clock.advance(5400);
  assert.equal(ctx.state.controlsVisible, true);
  clock.advance(200);
  assert.equal(ctx.state.controlsVisible, false);
});

// --- Progress bar: Left and Right seek, not move focus ---------------------------------------

test('Left and Right on the focused progress bar send the keyboard seek events, and focus stays on it', () => {
  const { sent, nav } = load();
  nav.handleHudNavKey('ArrowLeft');           // enter at toggle
  nav.handleHudNavKey('ArrowUp');             // progress bar
  assert.equal(focusedId(nav), 'seek');
  nav.handleHudNavKey('ArrowLeft');
  nav.handleHudNavKey('ArrowRight');
  assert.deepEqual(actions(sent), [['keyboardSeekBack', 0], ['keyboardSeekForward', 0]]);
  assert.equal(focusedId(nav), 'seek');
});

test('a progress-bar seek never enters the scrub pipeline', () => {
  const { sent, nav } = load();
  nav.handleHudNavKey('ArrowLeft');
  nav.handleHudNavKey('ArrowUp');
  nav.handleHudNavKey('ArrowRight');
  assert.equal(nav.scrubbing(), false);
  assert.equal(sent.some(([type]) => type === 'scrubChange' || type === 'scrubFinish'), false);
});

test('Up and Down still leave the progress bar', () => {
  const { nav } = load();
  nav.handleHudNavKey('ArrowLeft');
  nav.handleHudNavKey('ArrowUp');             // progress bar
  nav.handleHudNavKey('ArrowDown');           // transport row again
  assert.notEqual(focusedId(nav), 'seek');
});

test('seeking from the progress bar keeps the HUD up while seeking, and it hides after the last press', () => {
  const { ctx, nav, clock } = revealed();
  clock.advance(4000);
  nav.handleHudNavKey('ArrowLeft');           // toggle at 4 s
  nav.handleHudNavKey('ArrowUp');             // progress bar
  clock.advance(4000);                        // 8 s
  assert.equal(ctx.state.controlsVisible, true);
  nav.handleHudNavKey('ArrowLeft');           // seek back at 8 s
  clock.advance(4000);                        // 12 s: 4 s after the seek
  assert.equal(ctx.state.controlsVisible, true, 'the seek restarted the window');
  clock.advance(5600);
  assert.equal(ctx.state.controlsVisible, false, 'hides after the last input');
});

// --- Subtitle panel open: the HUD nav steps aside, so keys never seek or change volume ----------

test('with the subtitle panel open, the HUD nav is unavailable and arrows fall through to Kotlin', () => {
  const { nav, ctx } = load({ keyboardPanelMode: 'subtitles' });
  assert.equal(nav.hudNavStateValue(), 0);
  nav.handleHudNavKey('ArrowLeft');
  assert.equal(focusedId(nav), null);
  assert.equal(ctx.state.controlsVisible, true);
});
