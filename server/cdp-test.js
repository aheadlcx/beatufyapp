/**
 * CDP 联调驱动：用 Chrome DevTools 协议控制 Edge/Chrome 打开直播页面，
 * 周期读取页面状态（连接状态/video 尺寸），最后截图。
 *
 * 用法：node cdp-test.js <url> <outPng> [观察秒数]
 */
'use strict';
const http = require('http');
const fs = require('fs');
const WebSocket = require('ws');

const url = process.argv[2] || 'http://127.0.0.1:8080/?autostart=viewer&room=room1';
const outPng = process.argv[3] || 'cdp.png';
const watchSec = Number(process.argv[4]) || 30;

function getJson(path) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: 9222, path }, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => { try { resolve(JSON.parse(d)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const targets = await getJson('/json');
  const page = targets.find(t => t.type === 'page');
  if (!page) throw new Error('no page target');
  const ws = new WebSocket(page.webSocketDebuggerUrl, { maxPayload: 64 * 1024 * 1024 });
  let seq = 0;
  const pending = new Map();

  function send(method, params) {
    const id = ++seq;
    ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
  }

  ws.on('message', d => {
    const m = JSON.parse(d);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      if (m.error) p.reject(new Error(m.error.message));
      else p.resolve(m.result);
    }
  });

  await new Promise(r => ws.on('open', r));
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.navigate', { url });
  console.log('[cdp] navigating to', url);

  // 观察期间周期打印页面状态
  const probe = setInterval(async () => {
    try {
      const r = await send('Runtime.evaluate', {
        expression: 'JSON.stringify({status: document.getElementById("status").textContent,'
            + ' conn: document.getElementById("connState").textContent,'
            + ' vw: document.getElementById("remoteVideo").videoWidth,'
            + ' vh: document.getElementById("remoteVideo").videoHeight})',
        returnByValue: true,
      });
      console.log('[page]', r.result.value);
    } catch (e) { /* 页面跳转中忽略 */ }
  }, 3000);

  await sleep(watchSec * 1000);
  clearInterval(probe);

  const shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(outPng, Buffer.from(shot.data, 'base64'));
  console.log('[cdp] screenshot saved:', outPng);
  ws.close();
  process.exit(0);
})().catch(e => { console.error('[cdp] failed:', e.message); process.exit(1); });
