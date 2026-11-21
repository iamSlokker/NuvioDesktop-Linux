// The Linux HUD's playback prompts (next-episode card, skip prompt, skip-submit toast) take presses
// on themselves, never on their rewritten labels. WebKitGTK drops the click of a press whose text
// node was replaced before the release, and renderNativePlaybackPrompts rewrites these labels with
// every playerUpdate; checked here against the real CSS, JS and HTML, no browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const ui = path.join(__dirname, '../../desktopMain/resources/player-ui');
const css = fs.readFileSync(path.join(ui, 'controls.css'), 'utf8');
const js = fs.readFileSync(path.join(ui, 'controls.js'), 'utf8');
const html = fs.readFileSync(path.join(ui, 'controls.html'), 'utf8');

const prompts = ['.next-episode-card', '.skip-prompt', '.skip-submit-toast'];

test('Linux keeps presses off the prompts\' labels', () => {
  const rule = css.match(/\.player\.linux-host :is\(([^)]*)\) \* \{\s*pointer-events: none;\s*\}/);
  assert.ok(rule, 'Linux pointer-events rule present');
  assert.deepEqual(rule[1].split(',').map(s => s.trim()), prompts);
});

test('those are labels renderNativePlaybackPrompts rewrites on every update', () => {
  const start = js.indexOf('const renderNativePlaybackPrompts = () => {');
  const end = js.indexOf('\n};', start);
  assert.ok(start >= 0 && end > start);
  const body = js.slice(start, end);
  for (const label of ['skipPromptLabel', 'nextEpisodeHeader', 'nextEpisodeTitle', 'nextEpisodeStatus', 'nextEpisodeAction']) {
    assert.match(body, new RegExp(`${label}\\.textContent = `), label);
  }
  const toast = js.slice(js.indexOf('const renderSkipSubmitToast = () => {'));
  assert.match(toast.slice(0, toast.indexOf('\n};')), /skipSubmitToastTitle\.textContent = /);
  // renderChrome, run by every playerUpdate, renders the prompts.
  const chrome = js.slice(js.indexOf('const renderChrome = () => {'));
  assert.match(chrome.slice(0, chrome.indexOf('\n};')), /renderNativePlaybackPrompts\(\);/);
});

test('each prompt is one button with no control inside it', () => {
  for (const id of ['nextEpisodeCard', 'skipPrompt', 'skipSubmitToast']) {
    const open = html.match(new RegExp(`<button [^>]*id="${id}"[^>]*>`));
    assert.ok(open, id);
    const inner = html.slice(open.index + open[0].length, html.indexOf('</button>', open.index));
    assert.doesNotMatch(inner, /<(button|input|a|select|textarea)\b/, id);
  }
});

test('Windows and macOS keep upstream behaviour', () => {
  // A rule that sends these prompts' children's presses elsewhere: "<prompt> *" or "<prompt> span".
  const unscoped = css.replace(/\/\*[\s\S]*?\*\//g, '').split('}').filter(block => {
    const [selector, body = ''] = block.split('{');
    return /(next-episode-card|skip-prompt|skip-submit-toast)\)?\s+(\*|span|img)/.test(selector) &&
      /pointer-events:\s*none/.test(body) && !/linux-host/.test(selector);
  });
  assert.deepEqual(unscoped, []);
});
