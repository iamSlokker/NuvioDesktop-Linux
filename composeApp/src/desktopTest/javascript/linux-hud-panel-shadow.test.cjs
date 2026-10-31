// The Linux HUD drops the panels' blurred drop shadow (CPU-painted WebKitGTK re-blurred it on every
// scroll repaint); the rule must stay last-winning and Linux-only. No browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const ui = path.join(__dirname, '../../desktopMain/resources/player-ui');
const css = fs.readFileSync(path.join(ui, 'controls.css'), 'utf8');

test('Linux panels have no drop shadow, and only under linux-host', () => {
  const selector = '.player.linux-host :is(.track-panel, .track-panel.wide, .iptv-panel)';
  const at = css.lastIndexOf(selector + ' {');
  assert.ok(at >= 0, 'Linux rule present');
  const body = css.slice(at, css.indexOf('}', at));
  assert.match(body, /box-shadow: none !important/);
  // Later panel shadows would need !important to beat it; keep it the last box-shadow on a panel.
  const later = css.slice(css.indexOf('}', at));
  assert.doesNotMatch(later, /(track-panel|iptv-panel)[^{}]*\{[^}]*box-shadow[^;]*!important/);
});

test('Windows and macOS panels keep their shadow', () => {
  assert.match(css, /\.track-panel \{\s*border: 1px solid var\(--glass-edge\);[^}]*box-shadow: 0 28px 80px/);
});
