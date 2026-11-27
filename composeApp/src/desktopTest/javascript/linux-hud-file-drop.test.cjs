// Files dropped on the player (upstream 161b8bc9): the real controls.js block, on WebKitGTK and WebView2.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../desktopMain/resources/player-ui/controls.js'), 'utf8');
const start = source.indexOf('// --- Files dropped on the player');
const end = source.indexOf('\n})();\n', start);
assert.ok(start >= 0 && end > start, 'production file-drop block must exist');
const block = source.slice(start, end + '\n})();\n'.length);

function load(window) {
  const listeners = {};
  window.addEventListener = (type, handler) => { listeners[type] = handler; };
  vm.runInNewContext(block, { window });
  return listeners;
}

function event(types, files) {
  const result = { prevented: false, dataTransfer: { types, files, dropEffect: 'none' } };
  result.preventDefault = () => { result.prevented = true; };
  return result;
}

test('on WebKitGTK a file drop is announced with its count; the bridge reads the paths from GTK', () => {
  const posted = [];
  const listeners = load({ webkit: { messageHandlers: { player: { postMessage: message => posted.push(message) } } } });
  const over = event(['Files'], []);
  listeners.dragover(over);
  assert.equal(over.prevented, true, 'without preventDefault WebKit would open the file itself');
  assert.equal(over.dataTransfer.dropEffect, 'copy');
  const drop = event(['Files'], [{ name: 'a.srt' }, { name: 'b.ass' }]);
  listeners.drop(drop);
  assert.equal(drop.prevented, true);
  // The message is an object of the page's realm, so it is compared by value.
  assert.equal(JSON.stringify(posted), JSON.stringify([{ type: 'filesDropped', value: 2 }]));
});

test('a drag that carries no files is left to the page', () => {
  const posted = [];
  const listeners = load({ webkit: { messageHandlers: { player: { postMessage: message => posted.push(message) } } } });
  const drop = event(['text/plain'], []);
  listeners.drop(drop);
  assert.equal(drop.prevented, false);
  assert.deepEqual(posted, []);
});

test('WebView2 still hands the files over as additional objects', () => {
  const sent = [];
  const files = [{ name: 'a.srt' }];
  const listeners = load({ chrome: { webview: {
    postMessage() { throw new Error('plain postMessage carries no files'); },
    postMessageWithAdditionalObjects: (message, objects) => sent.push([message, objects]),
  } } });
  listeners.drop(event(['Files'], files));
  assert.equal(sent.length, 1);
  assert.equal(JSON.stringify(sent[0][0]), JSON.stringify({ type: 'filesDropped', value: 1 }));
  assert.equal(sent[0][1], files);
});

test('a host with neither bridge listens to nothing', () => {
  assert.equal(Object.keys(load({})).length, 0);
});
