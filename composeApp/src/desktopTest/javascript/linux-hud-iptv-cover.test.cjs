// Execute the actual controls.js Linux channel-cover release and check its CSS rule; no browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const ui = path.join(__dirname, '../../desktopMain/resources/player-ui');
const source = fs.readFileSync(path.join(ui, 'controls.js'), 'utf8');
const start = source.indexOf('const IPTV_LOADING_FADE_MS');
assert.ok(start >= 0, 'production channel-cover release must exist');
const end = source.indexOf('\nconst renderOpeningOverlay', start);
assert.ok(end > start);
const actual = source.slice(start, end);

function fixture(linuxHost) {
  const classes = new Set(['opening-overlay', 'visible', 'iptv-loading']);
  const timers = [];
  const ctx = {
    linuxHost,
    openingOverlay: { classList: { contains: name => classes.has(name), remove: name => classes.delete(name) } },
    window: {
      setTimeout: (fn, delay) => { timers.push({ fn, delay }); return timers.length; },
      clearTimeout: id => { if (id) timers[id - 1].fn = null; },
    },
  };
  // A const in the slice stays local to the script, so the release is handed out explicitly.
  vm.runInNewContext(actual + '\nthis.release = scheduleIptvLoadingClassRelease;', ctx);
  return { classes, timers, release: showing => ctx.release(showing) };
}

test('Linux sheds iptv-loading only after the fade, with the cover hidden', () => {
  const f = fixture(true);
  f.release(true);
  assert.equal(f.timers.length, 0, 'a showing cover schedules nothing');
  f.release(false);
  f.release(false);
  assert.equal(f.timers.length, 1, 'one release per fade');
  assert.ok(f.timers[0].delay > 320, 'after the 320ms fade');
  assert.ok(f.classes.has('iptv-loading'), 'kept through the fade');
  f.classes.delete('visible');
  f.timers[0].fn();
  assert.ok(!f.classes.has('iptv-loading'));
});
test('a cover shown again before the fade ends keeps its look', () => {
  const f = fixture(true);
  f.release(false);
  f.release(true);
  assert.equal(f.timers[0].fn, null, 'the release was cancelled');
  assert.ok(f.classes.has('iptv-loading'));
});
test('a release that fires on a visible cover leaves it alone', () => {
  const f = fixture(true);
  f.release(false);
  f.timers[0].fn();
  assert.ok(f.classes.has('iptv-loading'), 'visible again: nothing removed');
});
test('Windows and macOS keep upstream behaviour', () => {
  const f = fixture(false);
  f.release(false);
  assert.equal(f.timers.length, 0);
  assert.ok(f.classes.has('iptv-loading'));
});
test('the shimmer layer is restored on the channel cover for Linux only', () => {
  const css = fs.readFileSync(path.join(ui, 'controls.css'), 'utf8');
  const rule = css.match(/\.player\.linux-host \.opening-overlay\.iptv-loading::before \{([^}]*)\}/);
  assert.ok(rule, 'Linux rule present');
  assert.match(rule[1], /display: block !important/);
  assert.match(rule[1], /animation: image-shimmer/);
  assert.match(source, /root\.classList\.toggle\("linux-host", linuxHost\)/);
});
