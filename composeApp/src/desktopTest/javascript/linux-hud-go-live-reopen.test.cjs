// Only the click on Go live may reopen a channel. The automatic jumps at the start of a channel stay
// plain goLive: on a channel with no seekable range they would otherwise reopen it for ever. No browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const root = path.join(__dirname, '../../desktopMain');
const js = fs.readFileSync(path.join(root, 'resources/player-ui/controls.js'), 'utf8');
const controller = fs.readFileSync(path.join(root, 'kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt'), 'utf8');

test('the badge click asks for goLiveUser on Linux and goLive elsewhere', () => {
  const click = js.slice(js.indexOf('iptvLiveBadge.addEventListener("click"'));
  const handler = click.slice(0, click.indexOf('});'));
  assert.match(handler, /send\(linuxHost \? "goLiveUser" : "goLive", IPTV_GO_LIVE_CUSHION_MS\)/);
});

test('every other sender stays plain goLive', () => {
  const senders = js.match(/send\([^;]*"goLive[^;]*;/g);
  assert.equal(senders.length, 3, 'the opening jump, the Linux catch-up, the click');
  assert.equal(senders.filter(line => line.includes('goLiveUser')).length, 1);
});

test('the reopen hangs off goLiveUser alone', () => {
  const plain = controller.slice(controller.indexOf('"goLive" -> {'), controller.indexOf('"goLiveUser" -> {'));
  assert.ok(!plain.includes('IPTV_REOPEN_FOR_LIVE_EVENT'));
  const user = controller.slice(controller.indexOf('"goLiveUser" -> {'), controller.indexOf('"scrubFinish" -> {'));
  assert.ok(user.includes('IPTV_REOPEN_FOR_LIVE_EVENT'));
});
