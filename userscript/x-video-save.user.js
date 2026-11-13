// ==UserScript==
// @name         X 视频保存
// @namespace    https://github.com/qifangcat/x-video-save
// @version      0.1.0
// @description  在 X 网页的推文中保存可用的 MP4 视频。
// @match        https://x.com/*
// @match        https://twitter.com/*
// @run-at       document-start
// @grant        unsafeWindow
// @grant        GM_download
// @connect      video.twimg.com
// ==/UserScript==

(function () {
  'use strict';

  const page = unsafeWindow;
  const videos = new Map();
  const maxTweets = 1000;

  // Only consume X's own API responses already requested by the page.
  function isApi(url) {
    try {
      const u = new URL(String(url), location.href);
      return (u.hostname === 'x.com' || u.hostname === 'twitter.com' || u.hostname === 'api.twitter.com') &&
        (u.pathname.startsWith('/i/api/') || u.hostname === 'api.twitter.com');
    } catch (_) { return false; }
  }

  function safeVideoUrl(value) {
    try {
      const u = new URL(value);
      return u.protocol === 'https:' && u.hostname === 'video.twimg.com' && !u.username && !u.password;
    } catch (_) { return false; }
  }

  function collect(data) {
    const seen = new WeakSet();
    const stack = [data];
    while (stack.length) {
      const item = stack.pop();
      if (!item || typeof item !== 'object' || seen.has(item)) continue;
      seen.add(item);
      const legacy = item.legacy;
      const id = item.rest_id || (legacy && legacy.id_str) || item.id_str;
      const entities = (legacy && legacy.extended_entities) || item.extended_entities;
      if (/^\d+$/.test(String(id || '')) && entities && Array.isArray(entities.media)) {
        const media = entities.media.filter(m => m && (m.type === 'video' || m.type === 'animated_gif'))
          .map(m => {
            const variants = m.video_info && m.video_info.variants;
            return (Array.isArray(variants) ? variants : [])
              .filter(v => v && v.content_type === 'video/mp4' && safeVideoUrl(v.url))
              .sort((a, b) => (Number(b.bitrate) || 0) - (Number(a.bitrate) || 0))
              .map(v => ({ url: v.url, bitrate: Number(v.bitrate) || 0 }));
          });
        if (media.length) {
          videos.delete(String(id));
          videos.set(String(id), media);
          if (videos.size > maxTweets) videos.delete(videos.keys().next().value);
        }
      }
      for (const value of Object.values(item)) {
        if (value && typeof value === 'object') stack.push(value);
      }
    }
  }

  const originalFetch = page.fetch;
  page.fetch = function (...args) {
    const result = originalFetch.apply(this, args);
    const request = args[0];
    if (isApi(typeof request === 'string' || request instanceof URL ? request : request && request.url)) {
      result.then(response => response.clone().json().then(collect).catch(() => {})).catch(() => {});
    }
    return result;
  };
  const xhrOpen = page.XMLHttpRequest.prototype.open;
  const xhrSend = page.XMLHttpRequest.prototype.send;
  page.XMLHttpRequest.prototype.open = function (method, url, ...rest) {
    this.__xVideoSaveApi = isApi(url);
    return xhrOpen.call(this, method, url, ...rest);
  };
  page.XMLHttpRequest.prototype.send = function (...args) {
    if (this.__xVideoSaveApi) this.addEventListener('load', function () {
      try {
        if (!this.responseType || this.responseType === 'text') collect(JSON.parse(this.responseText));
        else if (this.responseType === 'json') collect(this.response);
      } catch (_) { /* Non-JSON responses are expected. */ }
    }, { once: true });
    return xhrSend.apply(this, args);
  };

  function tweetId(article) {
    // The timestamp belongs to the post itself, unlike links inside quoted posts.
    const time = article.querySelector('time');
    const link = time && time.closest('a[href*="/status/"]');
    const match = link && link.getAttribute('href').match(/\/status\/(\d+)/);
    return match && match[1];
  }

  function download(id, index, variant) {
    if (!safeVideoUrl(variant.url)) return;
    GM_download({
      url: variant.url,
      name: `X-${id}-${index + 1}.mp4`,
      saveAs: true,
      onerror: () => alert('下载失败。请检查油猴下载模式、网络连接，或刷新页面后重试。'),
      ontimeout: () => alert('下载超时，请刷新页面后重试。')
    });
  }

  function openMenu(id) {
    const old = document.getElementById('x-video-save-dialog');
    if (old) old.remove();
    const backdrop = document.createElement('div');
    backdrop.id = 'x-video-save-dialog';
    const panel = document.createElement('div');
    panel.className = 'xvs-panel';
    const title = document.createElement('strong');
    title.textContent = '保存视频';
    panel.append(title);
    const entries = videos.get(id);
    if (!entries) {
      const msg = document.createElement('p');
      msg.textContent = '暂未获取这条帖子的媒体信息。请刷新页面，等待视频加载后重试。';
      panel.append(msg);
    } else {
      entries.forEach((variants, index) => {
        if (!variants.length) {
          const msg = document.createElement('p');
          msg.textContent = `视频 ${index + 1}：没有可用的 MP4 地址。`;
          panel.append(msg);
          return;
        }
        variants.forEach((variant, quality) => {
          const button = document.createElement('button');
          const rate = variant.bitrate ? `${(variant.bitrate / 1000000).toFixed(2)} Mbps` : '码率未知';
          button.textContent = `视频 ${index + 1} · ${rate}${quality === 0 ? '（最高）' : ''}`;
          button.addEventListener('click', () => { backdrop.remove(); download(id, index, variant); });
          panel.append(button);
        });
      });
    }
    const close = document.createElement('button');
    close.textContent = '关闭';
    close.addEventListener('click', () => backdrop.remove());
    panel.append(close);
    backdrop.addEventListener('click', event => { if (event.target === backdrop) backdrop.remove(); });
    backdrop.append(panel);
    document.body.append(backdrop);
  }

  function scan() {
    for (const article of document.querySelectorAll('article[data-testid="tweet"]')) {
      if (article.querySelector('.x-video-save-button') || !article.querySelector('[data-testid="videoPlayer"], video')) continue;
      const id = tweetId(article);
      if (!id) continue;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'x-video-save-button';
      button.textContent = '↓ 保存视频';
      button.addEventListener('click', event => {
        event.preventDefault();
        event.stopPropagation();
        const currentId = tweetId(article);
        if (currentId) openMenu(currentId);
      });
      const group = article.querySelector('[role="group"]');
      (group || article).append(button);
    }
  }

  function start() {
    const style = document.createElement('style');
    style.textContent = '.x-video-save-button{color:#1d9bf0;background:transparent;border:0;cursor:pointer;font:inherit;padding:8px}.x-video-save-button:hover{text-decoration:underline}#x-video-save-dialog{position:fixed;inset:0;z-index:2147483647;background:#0009;display:flex;align-items:center;justify-content:center}.xvs-panel{background:#fff;color:#111;border-radius:16px;padding:20px;min-width:260px;max-width:min(90vw,480px);max-height:80vh;overflow:auto;font:15px sans-serif}.xvs-panel button{display:block;width:100%;margin-top:10px;padding:10px;border:1px solid #ccc;border-radius:8px;background:#f7f9fa;color:#111;cursor:pointer}';
    document.head.append(style);
    let scheduled = false;
    new MutationObserver(() => {
      if (scheduled) return;
      scheduled = true;
      setTimeout(() => { scheduled = false; scan(); }, 200);
    }).observe(document.body, { childList: true, subtree: true });
    scan();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
