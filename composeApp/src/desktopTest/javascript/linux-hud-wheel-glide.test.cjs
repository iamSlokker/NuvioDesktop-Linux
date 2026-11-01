// The Linux HUD glides notch-sized wheel steps itself (WebKitGTK's smooth scrolling is off in the
// overlay, because its ease-in restarted on every wheel event). Runs the fork:linux block from
// controls.js against a fake window and panel. No browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const ui = path.join(__dirname, '../../desktopMain/resources/player-ui');
const js = fs.readFileSync(path.join(ui, 'controls.js'), 'utf8');
const overlay = fs.readFileSync(path.join(__dirname, '../../desktopMain/native/linux/controls_overlay.cpp'), 'utf8');

const start = js.indexOf('// fork:linux - wheel scrolling in the HUD');
const block = js.slice(start);

function harness(linuxHost) {
  const frames = [];
  let wheel = null;
  const body = { parentElement: null };
  const panel = { scrollHeight: 1400, clientHeight: 565, _top: 0, parentElement: body };
  Object.defineProperty(panel, 'scrollTop', {
    get() { return this._top; },
    set(value) { this._top = Math.round(Math.min(this.scrollHeight - this.clientHeight, Math.max(0, value))); },
  });
  class Element {}
  Object.setPrototypeOf(panel, Element.prototype);
  const context = {
    linuxHost,
    Element,
    WheelEvent: { DOM_DELTA_PIXEL: 0 },
    document: { body },
    getComputedStyle: el => ({ overflowY: el === panel ? 'auto' : 'visible' }),
    requestAnimationFrame: callback => frames.push(callback),
    window: { addEventListener: (type, handler) => { if (type === 'wheel') wheel = handler; } },
    Math,
  };
  vm.runInNewContext(block, context);
  const send = (deltaY, extra = {}) => {
    const event = { deltaY, deltaX: 0, deltaMode: 0, ctrlKey: false, defaultPrevented: false, target: panel, ...extra };
    event.preventDefault = () => { event.prevented = true; };
    wheel(event);
    return event;
  };
  const frame = () => { const pending = frames.splice(0); pending.forEach(callback => callback()); };
  return { panel, send, frame, frames, registered: () => wheel !== null };
}

test('WebKitGTK smooth scrolling is off in the Linux overlay', () => {
  assert.match(overlay, /webkit_settings_set_enable_smooth_scrolling\(settings, FALSE\)/);
});

test('only the Linux HUD takes the wheel', () => {
  assert.ok(start > 0, 'fork:linux block present');
  assert.equal(harness(false).registered(), false);
  assert.equal(harness(true).registered(), true);
});

test('a notch moves the panel from the first frame and eases to its target', () => {
  const h = harness(true);
  assert.ok(h.send(120).prevented);
  h.frame();
  assert.ok(h.panel.scrollTop > 0, 'moves on the first frame');
  for (let i = 0; i < 30; i++) h.frame();
  assert.equal(h.panel.scrollTop, 120);
  assert.equal(h.frames.length, 0, 'the glide ends');
});

test('notches in quick succession add up instead of restarting', () => {
  const h = harness(true);
  const seen = [];
  for (let i = 0; i < 5; i++) { h.send(120); h.frame(); seen.push(h.panel.scrollTop); }
  for (let i = 1; i < seen.length; i++) assert.ok(seen[i] > seen[i - 1], `keeps moving: ${seen}`);
  for (let i = 0; i < 40; i++) h.frame();
  assert.equal(h.panel.scrollTop, 600);
});

test('the glide stops at the end of the panel', () => {
  const h = harness(true);
  for (let i = 0; i < 20; i++) h.send(120);
  for (let i = 0; i < 60; i++) h.frame();
  assert.equal(h.panel.scrollTop, 835);
  assert.equal(h.frames.length, 0);
  assert.equal(h.send(120).prevented, undefined, 'at the end the wheel passes through');
});

test('fine deltas, taken wheels and zoom gestures are left alone', () => {
  const h = harness(true);
  assert.equal(h.send(12).prevented, undefined, 'touchpad or high-resolution wheel');
  assert.equal(h.send(120, { defaultPrevented: true }).prevented, undefined, 'another handler took it');
  assert.equal(h.send(120, { ctrlKey: true }).prevented, undefined, 'Ctrl+wheel');
  assert.equal(h.send(120, { deltaX: 200 }).prevented, undefined, 'horizontal');
  assert.equal(h.frames.length, 0);
});

test('moving the panel another way ends the glide', () => {
  const h = harness(true);
  h.send(360);
  h.frame();
  h.panel.scrollTop = 20; // e.g. the scrollbar
  h.frame();
  assert.equal(h.panel.scrollTop, 20);
  assert.equal(h.frames.length, 0);
});
