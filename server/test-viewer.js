/** 最小信令测试观众：join 后打印收到的每条消息（验证主播是否发 offer/candidate）。 */
'use strict';
const WebSocket = require('ws');
const room = process.argv[2] || 'room1';
const ws = new WebSocket('ws://127.0.0.1:8080');
ws.on('open', () => { console.log('[test-viewer] joined request sent, room=' + room); ws.send(JSON.stringify({ type: 'join', room, role: 'viewer' })); });
ws.on('message', (d) => console.log('[recv]', d.toString()));
ws.on('close', (c) => console.log('[closed]', c));
ws.on('error', (e) => console.log('[error]', e.message));
setTimeout(() => { console.log('[test-viewer] 30s done'); process.exit(0); }, 30000);
