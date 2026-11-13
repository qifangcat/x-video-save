const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync(require('node:path').join(__dirname, '../userscript/x-video-save.user.js'), 'utf8');

function setup(payload) {
  class Element {
    constructor(tag) { this.tag = tag; this.children = []; this.handlers = {}; this.textContent = ''; }
    append(...nodes) { this.children.push(...nodes); for (const node of nodes) node.parent = this; }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(x => x !== this); }
    addEventListener(event, handler) { this.handlers[event] = handler; }
    click() { this.handlers.click?.({ target: this, preventDefault() {}, stopPropagation() {} }); }
  }
  const group = new Element('group');
  const article = {
    querySelector(selector) {
      if (selector === '.x-video-save-button') return group.children.find(x => x.className === 'x-video-save-button');
      if (selector === '[data-testid="videoPlayer"], video') return {};
      if (selector === 'time') return { closest: () => ({ getAttribute: () => '/author/status/123' }) };
      if (selector === '[role="group"]') return group;
      return null;
    }
  };
  const body = new Element('body');
  const head = new Element('head');
  const document = {
    readyState: 'complete', body, head,
    createElement: tag => new Element(tag),
    querySelectorAll: () => [article],
    getElementById: id => body.children.find(x => x.id === id)
  };
  const calls = [];
  class XHR { open() {} send() {} addEventListener() {} }
  const response = { clone: () => ({ json: async () => payload }) };
  const page = { fetch: async () => response, XMLHttpRequest: XHR };
  const context = { unsafeWindow: page, document, location: { href: 'https://x.com/home' }, URL,
    MutationObserver: class { observe() {} }, setTimeout, GM_download: options => calls.push(options), alert() {} };
  vm.runInNewContext(source, context);
  return { page, body, group, calls };
}

const fixture = {
  data: { result: { rest_id: '123', legacy: { extended_entities: { media: [
    { type: 'video', video_info: { variants: [
      { content_type: 'video/mp4', bitrate: 320000, url: 'https://video.twimg.com/low.mp4' },
      { content_type: 'video/mp4', bitrate: 2048000, url: 'https://video.twimg.com/high.mp4' },
      { content_type: 'video/mp4', bitrate: 9999999, url: 'https://evil.example/steal.mp4' },
      { content_type: 'application/x-mpegURL', url: 'https://video.twimg.com/master.m3u8' }
    ] } },
    { type: 'animated_gif', video_info: { variants: [{ content_type: 'video/mp4', url: 'https://video.twimg.com/gif.mp4' }] } },
    { type: 'photo' }
  ] } } } }
};

test('captures X response and presents safe MP4 variants in bitrate order', async () => {
  const { page, body, group, calls } = setup(fixture);
  await page.fetch('https://x.com/i/api/graphql/example');
  await new Promise(resolve => setImmediate(resolve));
  group.children[0].click();
  const panel = body.children[0].children[0];
  assert.deepEqual(panel.children.map(x => x.textContent), [
    '保存视频', '视频 1 · 2.05 Mbps（最高）', '视频 1 · 0.32 Mbps', '视频 2 · 码率未知（最高）', '关闭'
  ]);
  panel.children[1].click();
  assert.equal(calls[0].url, 'https://video.twimg.com/high.mp4');
  assert.equal(calls[0].name, 'X-123-1.mp4');
  assert.equal(body.children.length, 0);
});

test('does not consume unrelated sites and explains missing media', async () => {
  const { page, body, group, calls } = setup(fixture);
  await page.fetch('https://example.com/i/api/graphql/example');
  await new Promise(resolve => setImmediate(resolve));
  group.children[0].click();
  assert.match(body.children[0].children[0].children[1].textContent, /暂未获取/);
  assert.equal(calls.length, 0);
});
