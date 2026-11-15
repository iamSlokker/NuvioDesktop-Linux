// Execute the real speed-wrap block from controls.js, plus the two speed helpers it calls, in a vm
// sandbox. No browser or window.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');

function slice(startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start);
  assert.ok(start >= 0 && end > start, `${startMarker} must exist`);
  return source.slice(start, end);
}
// A top-level `const name = ... };` helper, sliced whole.
function helper(name) {
  const start = source.indexOf(`const ${name} =`);
  assert.ok(start >= 0, `${name} must exist`);
  return source.slice(start, source.indexOf('\n};', start) + 3);
}

const block = slice('// >>> linux-speed-wrap', '// <<< linux-speed-wrap');
const helpers = helper('parsedPlaybackSpeed') + helper('stepPlaybackSpeed');

function load({ label = '3x', linuxHost = true } = {}) {
  const sent = [];
  const pills = [];
  const ctx = {
    linuxHost,
    state: { playbackSpeedLabel: label },
    speedLabel: { textContent: label },
    hudText: (key, fallback) => fallback,
    window: { nuvioShowPresetPill: (title, value) => pills.push([title, value]) },
    send: (type, value) => sent.push([type, value]),
  };
  vm.createContext(ctx);
  vm.runInContext(helpers + block + '\nglobalThis.__cycle = cycleSpeedFromButton;', ctx);
  return { ctx, sent, pills, cycle: ctx.__cycle };
}

test('past 4x, the next press sends an absolute 1x instead of a clamped step', () => {
  const { ctx, sent, pills, cycle } = load({ label: '4x' });
  cycle();
  assert.deepEqual(sent, [['setPlaybackSpeed', 1]]);
  assert.equal(ctx.state.playbackSpeedLabel, '1x');
  assert.equal(ctx.speedLabel.textContent, '1x');
  assert.deepEqual(pills, [['Playback speed', '1x']]);
});

test('a second press after the wrap steps up from 1x, not from the stale 4x label', () => {
  const { sent, cycle } = load({ label: '4x' });
  cycle();
  cycle();
  assert.deepEqual(sent, [['setPlaybackSpeed', 1], ['keyboardSpeedStep', 1]]);
});

test('below the maximum, the press is the ordinary step up', () => {
  const { sent, cycle } = load({ label: '3x' });
  cycle();
  assert.deepEqual(sent, [['keyboardSpeedStep', 1]]);
});

test('fine-mode speeds just under 4x still step, and only 4.0x wraps', () => {
  const below = load({ label: '3.9x' });
  below.cycle();
  assert.deepEqual(below.sent, [['keyboardSpeedStep', 1]]);
  const at = load({ label: '4x' });
  at.cycle();
  assert.deepEqual(at.sent, [['setPlaybackSpeed', 1]]);
});

test('Windows and macOS keep the clamped step at 4x', () => {
  const { sent, cycle } = load({ label: '4x', linuxHost: false });
  cycle();
  assert.deepEqual(sent, [['keyboardSpeedStep', 1]]);
});
