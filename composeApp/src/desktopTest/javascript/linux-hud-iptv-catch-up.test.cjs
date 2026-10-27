// Runs the real controls.js live TV opening logic (auto Go live and the Linux catch-up) against a
// simulated channel: 250 ms playerUpdates as the Linux bridge sends them, a provider connect burst
// shaped like the owner's mpv log, and Go live landing the way the bridge's seekToCachedLiveEdge
// does. No browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');
const start = source.indexOf('const IPTV_LIVE_SLACK_MS');
const end = source.indexOf('\nconst renderIptvLive', start);
assert.ok(start >= 0 && end > start, 'production live TV opening logic must exist');
const actual = source.slice(start, end);

// Times in ms of stream time (mpv's time-pos) and of wall time since playback started.
// The owner's log (channel 14103): playback began at 0.7 s with the received end at 16.8 s; the
// end then ran ~2.4x real time until it reached the live edge, 29.4 s ahead of playback, ~9 s in.
function channel({ linuxHost, startBehindMs = 16100, liveAheadMs = 28700, burstRate = 2.4, script = () => {} }) {
  let now = 1_000_000;
  const sent = [];
  const ctx = {
    linuxHost,
    Date: { now: () => now },
    state: {},
    send: (type, value) => sent.push({ type, value, at: now }),
  };
  vm.runInNewContext(`${actual}
this.render = iptv => { maybeAutoGoLive(iptv); if (typeof maybeLinuxIptvCatchUp === 'function') maybeLinuxIptvCatchUp(iptv); };`, ctx);
  const t0 = now;
  const firstPos = 700;
  let pos = firstPos;
  let received = firstPos + startBehindMs;
  const liveAt = elapsed => firstPos + liveAheadMs + elapsed;
  let playing = true;
  let loadingTicks = 0;
  const player = {
    seekBy: offset => { pos = Math.max(firstPos, pos + offset); loadingTicks = 1; },
    pause: () => { playing = false; },
    play: () => { playing = true; },
    stall: ticks => { loadingTicks = ticks; },
  };
  const iptv = { channelLabel: 'Ch 14103' };
  const behind = [];
  for (let tick = 0; tick < 140; tick++) {
    const elapsed = now - t0;
    script(elapsed, player);
    ctx.state = { ...ctx.state, positionMs: pos, bufferedMs: received, isPlaying: playing, isLoading: loadingTicks > 0, iptv };
    const before = sent.length;
    ctx.render(iptv);
    for (const message of sent.slice(before)) {
      // seekToCachedLiveEdge: cushion short of the received end, skipped when under 3 s ahead.
      const target = received - message.value;
      message.landed = target > pos + 3000;
      if (message.landed) { pos = target; loadingTicks = 1; }
    }
    now += 250;
    const step = now - t0 - elapsed;
    received = Math.min(liveAt(now - t0), received + step * burstRate);
    if (loadingTicks > 0) loadingTicks--;
    else if (playing) pos += step;
    behind.push(liveAt(now - t0) - pos);
  }
  return { goLive: sent.filter(m => m.type === 'goLive'), finalBehindMs: behind[behind.length - 1] };
}

test("Windows and macOS keep upstream's single opening decision", () => {
  const run = channel({ linuxHost: false });
  assert.equal(run.goLive.length, 1, 'only the opening jump');
  assert.ok(run.finalBehindMs > 20000, `still ${run.finalBehindMs} ms behind, as upstream`);
});

test('Linux goes live once more after a slow connect burst (the owner\'s log)', () => {
  const run = channel({ linuxHost: true });
  assert.equal(run.goLive.length, 2, 'the opening jump and one catch-up');
  assert.ok(run.goLive.every(m => m.landed));
  assert.ok(run.goLive[1].at - run.goLive[0].at > 8000, 'after the burst has slowed, not during it');
  assert.ok(run.finalBehindMs <= 15000, `ends LIVE, ${run.finalBehindMs} ms behind`);
});

test('Linux catches up a channel judged near live while the burst was still arriving', () => {
  const run = channel({ linuxHost: true, startBehindMs: 5000 });
  assert.equal(run.goLive.length, 1, 'no opening jump, one catch-up');
  assert.ok(run.finalBehindMs <= 15000, `ends LIVE, ${run.finalBehindMs} ms behind`);
  const upstream = channel({ linuxHost: false, startBehindMs: 5000 });
  assert.equal(upstream.goLive.length, 0);
  assert.ok(upstream.finalBehindMs > 25000);
});

test('a burst that is already in needs no catch-up', () => {
  const run = channel({ linuxHost: true, startBehindMs: 28700 });
  assert.equal(run.goLive.length, 1, 'the opening jump only');
  assert.ok(run.finalBehindMs <= 15000);
});

test("the viewer's rewind during the watch is never undone", () => {
  const run = channel({ linuxHost: true, script: (elapsed, p) => { if (elapsed === 5000) p.seekBy(-10000); } });
  assert.equal(run.goLive.length, 1, 'the opening jump only');
});

test('a pause during the watch ends it', () => {
  const run = channel({
    linuxHost: true,
    script: (elapsed, p) => { if (elapsed === 4000) p.pause(); if (elapsed === 6000) p.play(); },
  });
  assert.equal(run.goLive.length, 1);
});

test('a buffering stall is not taken for a seek', () => {
  const run = channel({ linuxHost: true, script: (elapsed, p) => { if (elapsed === 4000) p.stall(10); } });
  assert.equal(run.goLive.length, 2);
  assert.ok(run.finalBehindMs <= 15000 + 2500);
});

test('a burst that never slows is caught up when the watch ends', () => {
  // Received end ahead of real time for the whole watch: live is 60 s ahead, burst at 1.5x.
  const run = channel({ linuxHost: true, startBehindMs: 5000, liveAheadMs: 60000, burstRate: 1.5 });
  assert.equal(run.goLive.length, 1);
  assert.ok(run.goLive[0].at - 1_000_000 >= 20000, 'only once the 20 s watch is over');
});

test('renderIptvLive runs the catch-up right after the opening decision', () => {
  assert.match(source, /\n  maybeAutoGoLive\(iptv\);\n  maybeLinuxIptvCatchUp\(iptv\);\n/);
  assert.match(actual, /const maybeLinuxIptvCatchUp = iptv => \{\n  if \(!linuxHost\) return;/);
});
