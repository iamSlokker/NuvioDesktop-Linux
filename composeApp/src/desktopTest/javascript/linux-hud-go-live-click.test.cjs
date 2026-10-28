// The Linux HUD's live TV buttons take presses on themselves, never on their rewritten labels.
// WebKitGTK drops the click of a press whose text node was replaced before the release, and the
// labels are rewritten with every playerUpdate; checked here against the real CSS and JS, no browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const ui = path.join(__dirname, '../../desktopMain/resources/player-ui');
const css = fs.readFileSync(path.join(ui, 'controls.css'), 'utf8');
const js = fs.readFileSync(path.join(ui, 'controls.js'), 'utf8');
const html = fs.readFileSync(path.join(ui, 'controls.html'), 'utf8');

const rule = css.match(/((?:\.player\.linux-host [^,{]+,?\s*)+)\{\s*pointer-events: none;\s*\}/);

test('Linux keeps presses off the live badge and Channels labels', () => {
  assert.ok(rule, 'Linux pointer-events rule present');
  const selectors = rule[1].split(',').map(s => s.trim()).filter(Boolean);
  assert.deepEqual(selectors, ['.player.linux-host .iptv-live-badge *', '.player.linux-host #iptvChannelsButton *']);
});

test('those are the labels renderIptvLive rewrites on every update', () => {
  const start = js.indexOf('const renderIptvLive = () => {');
  const end = js.indexOf('\n};', start);
  assert.ok(start >= 0 && end > start);
  const body = js.slice(start, end);
  assert.match(body, /iptvLiveLabel\.textContent = /);
  assert.match(body, /iptvLiveBehind\.textContent = /);
  assert.match(body, /iptvChannelsLabel\.textContent = /);
  assert.match(html, /<button class="iptv-live-badge" id="iptvLiveBadge"[^>]*>\s*<span class="iptv-live-dot"[^>]*><\/span>\s*<span class="iptv-live-label" id="iptvLiveLabel"[^>]*>/);
  assert.match(html, /<button [^>]*id="iptvChannelsButton"[^>]*>.*<span id="iptvChannelsLabel"[^>]*>/);
});

test('Windows and macOS keep upstream behaviour', () => {
  // Every new pointer-events rule for these buttons is behind linux-host.
  const unscoped = css.split('}').filter(block => /(iptv-live-badge|#iptvChannelsButton)[^{]*\*[^{]*\{[^}]*pointer-events/.test(block) && !/linux-host/.test(block));
  assert.deepEqual(unscoped, []);
});
