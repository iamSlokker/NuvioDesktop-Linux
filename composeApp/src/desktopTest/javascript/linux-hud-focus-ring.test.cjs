// Static checks on controls.css: the seek bar's radius lives in one variable, and the Linux nav ring
// follows it. This guards the sync; it does not prove how the ring looks on screen.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const css = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.css'), 'utf8');

// Body of the first rule whose selector text contains `selector`, comments stripped.
function ruleBody(selector) {
  const stripped = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const index = stripped.indexOf(selector);
  assert.ok(index >= 0, `rule ${selector} must exist`);
  const open = stripped.indexOf('{', index);
  const close = stripped.indexOf('}', open);
  return stripped.slice(open + 1, close);
}

test('the bar radius is defined once on :root', () => {
  assert.match(css, /:root\s*\{[^}]*--hud-bar-radius:\s*999px;/);
});

test('both seek-track rules use the shared radius', () => {
  assert.match(ruleBody('.scrub::-webkit-slider-runnable-track'), /border-radius:\s*var\(--hud-bar-radius\)/);
  assert.match(ruleBody('.player.official-hud .scrub::-webkit-slider-runnable-track'), /border-radius:\s*var\(--hud-bar-radius\)/);
});

test('the Linux nav ring is scoped to the Linux host and follows the bar radius on the seek input', () => {
  assert.match(ruleBody('.player.linux-host .chrome-nav-focus'), /outline:\s*2px solid/);
  assert.match(ruleBody('.player.linux-host #seek.chrome-nav-focus'), /border-radius:\s*var\(--hud-bar-radius\)/);
});

test('no unscoped rule resets the nav ring radius to square', () => {
  const stripped = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const ringRules = stripped.match(/[^{}]*chrome-nav-focus[^{}]*\{[^}]*\}/g) || [];
  assert.ok(ringRules.length >= 2, 'the ring rules exist');
  for (const rule of ringRules) {
    assert.doesNotMatch(rule, /border-radius:\s*0\b/, rule.trim());
    assert.match(rule, /\.player\.linux-host/, 'every ring rule is Linux-scoped');
  }
});
