#!/usr/bin/env node
/**
 * Cielo 对话桥（容器内 Node 进程）。
 *
 * 架构参照 AstrMobile：
 *   entrypoint.sh 启动 QQ(NapCat) + bridge.js，两者在同一个 proot 容器里。
 *   NapCat 通过 websocketClients 主动连到 127.0.0.1:3001，桥收到消息后调 LLM 再回 send_msg。
 *
 * 这样做的好处：桥跟着容器走，不依赖 Android 前台服务，不会被系统回收。
 */

const fs = require('fs');
const dns = require('dns');

// 容器里 /etc/resolv.conf 可能是空的或指向宿主机地址，导致 EAI_AGAIN。
// 显式指定公共 DNS，解析失败再退回系统默认。
try {
  dns.setServers(['223.5.5.5', '119.29.29.29', '8.8.8.8']);
} catch (e) {
  console.log('[bridge] dns.setServers failed: ' + e.message);
}
const path = require('path');
const http = require('http');
const https = require('https');
const { URL } = require('url');
let WebSocket;
try {
  WebSocket = require('/app/napcat/node_modules/ws');   // 容器内：用 NapCat 自带的
} catch (e) {
  WebSocket = require('ws');                            // 宿主机测试：用 npm 装的
}

const BASE = process.env.BRIDGE_BASE || '/app';
const CONFIG_PATH = BASE + '/bridge-config.json';
const LOG_PATH = BASE + '/bridge.log';
const PORT = 3001;

// ---------------------------------------------------------------- 日志
function log(msg) {
  const line = new Date().toLocaleTimeString('zh-CN', { hour12: false }) + ' ' + msg;
  try { fs.appendFileSync(LOG_PATH, line + '\n'); } catch (e) {}
  console.log('[bridge] ' + msg);
}

// 日志超过 512KB 就截断，避免无限增长
function trimLog() {
  try {
    const st = fs.statSync(LOG_PATH);
    if (st.size > 512 * 1024) {
      const buf = fs.readFileSync(LOG_PATH);
      fs.writeFileSync(LOG_PATH, buf.slice(buf.length - 256 * 1024));
    }
  } catch (e) {}
}

// ---------------------------------------------------------------- 配置
function loadConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  } catch (e) {
    log('config load failed: ' + e.message);
    return null;
  }
}

// ---------------------------------------------------------------- LLM
function callModel(cfg, messages) {
  return new Promise((resolve, reject) => {
    const url = new URL(cfg.endpoint.replace(/\/+$/, '') + '/chat/completions');
    const payload = JSON.stringify({
      model: cfg.model,
      messages,
      temperature: cfg.temperature || 0.8,
      max_tokens: 600,
    });
    const lib = url.protocol === 'https:' ? https : http;
    const req = lib.request({
      hostname: url.hostname,
      port: url.port || (url.protocol === 'https:' ? 443 : 80),
      path: url.pathname + url.search,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + cfg.apiKey,
        'Content-Length': Buffer.byteLength(payload),
      },
      timeout: 90000,
    }, (res) => {
      let body = '';
      res.on('data', (c) => body += c);
      res.on('end', () => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          return reject(new Error('HTTP ' + res.statusCode + ': ' + body.slice(0, 300)));
        }
        try {
          const j = JSON.parse(body);
          const content = j.choices && j.choices[0] && j.choices[0].message
            ? j.choices[0].message.content : '';
          resolve((content || '').trim());
        } catch (e) {
          reject(new Error('bad response: ' + body.slice(0, 200)));
        }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
    req.write(payload);
    req.end();
  });
}

// ---------------------------------------------------------------- 上下文
const history = new Map();  // sessionKey -> [{role, content}]

function pushHistory(key, role, content, max) {
  if (!history.has(key)) history.set(key, []);
  const h = history.get(key);
  h.push({ role, content });
  while (h.length > max) h.shift();
}

// ---------------------------------------------------------------- 消息处理
/**
 * 把 OneBot 消息段拆成「文本 + 图片列表」。
 *
 * 图片段 NapCat 会带 url（QQ 图床直链），模型支持多模态时直接喂给它；
 * 否则退回 summary（如「[图片]」）作为占位，至少不会当空消息丢掉。
 */
function parseMessage(message) {
  const out = { text: '', images: [] };
  if (typeof message === 'string') { out.text = message; return out; }
  if (!Array.isArray(message)) return out;

  const parts = [];
  for (const seg of message) {
    if (!seg || !seg.type) continue;
    const d = seg.data || {};

    switch (seg.type) {
      case 'text':
        if (d.text) parts.push(d.text);
        break;
      case 'image':
        if (d.url) out.images.push(d.url);
        parts.push(d.summary || '[图片]');
        break;
      case 'record':
        parts.push(d.summary || '[语音]');
        break;
      case 'video':
        parts.push(d.summary || '[视频]');
        break;
      case 'face':
        parts.push('[表情]');
        break;
      case 'mface':
        parts.push(d.summary || '[表情]');
        break;
      case 'file':
        parts.push('[文件] ' + (d.name || ''));
        break;
      case 'at':
        break;
      default:
        break;
    }
  }
  out.text = parts.join('');
  return out;
}

function isAtMe(message, selfId) {
  if (!Array.isArray(message)) return false;
  return message.some((s) =>
    s && s.type === 'at' && s.data && String(s.data.qq) === String(selfId));
}

let selfId = 0;

async function onEvent(msg, conn) {
  let evt;
  try { evt = JSON.parse(msg); } catch (e) { return; }

  const postType = evt.post_type;

  if (postType === 'meta_event' && evt.meta_event_type === 'lifecycle') {
    selfId = evt.self_id || 0;
    log('lifecycle self_id=' + selfId);
    return;
  }

  if (postType !== 'message') return;

  const cfg = loadConfig();
  if (!cfg || !cfg.apiKey) { log('skip: no config'); return; }

  const type = evt.message_type;
  const userId = evt.user_id;
  const groupId = evt.group_id;
  if (String(userId) === String(selfId)) return;

  const parsed = parseMessage(evt.message);
  const rawText = parsed.text;
  if (!rawText.trim() && parsed.images.length === 0) {
    log('skip: 无可用内容');
    return;
  }

  const mentioned = isAtMe(evt.message, selfId);
  let shouldReply = false;
  if (type === 'private') shouldReply = cfg.replyPrivate !== false;
  else if (type === 'group') {
    shouldReply = cfg.groupMentionOnly === false ? true : mentioned;
  }
  if (!shouldReply) return;

  let clean = rawText.replace(/@\S+\s*/g, '').trim();
  if (!clean) clean = parsed.images.length > 0 ? '（发来一张图片）' : '';
  if (!clean) return;

  const sessionKey = type === 'group' ? 'g' + groupId : 'p' + userId;
  log('recv [' + sessionKey + '] ' + clean.slice(0, 40));

  const messages = [{ role: 'system', content: cfg.persona || '你是昔珞，说话自然、有情绪的女孩子。回复要短。' }];
  const h = history.get(sessionKey) || [];
  h.forEach((m) => messages.push(m));

  // 有图片就走多模态格式（OpenAI 兼容）：content 数组里放 text + image_url
  if (parsed.images.length > 0) {
    const content = [{ type: 'text', text: clean }];
    parsed.images.slice(0, 4).forEach((url) => {
      content.push({ type: 'image_url', image_url: { url } });
    });
    messages.push({ role: 'user', content });
  } else {
    messages.push({ role: 'user', content: clean });
  }

  let reply;
  try {
    log('llm request model=' + cfg.model);
    reply = await callModel(cfg, messages);
    log('llm response ' + reply.length + ' chars');
  } catch (e) {
    log('llm error: ' + e.message);
    return;
  }
  if (!reply) return;

  pushHistory(sessionKey, 'user', clean, cfg.maxHistory || 8);
  pushHistory(sessionKey, 'assistant', reply, cfg.maxHistory || 8);

  const outMsg = type === 'group'
    ? [
        { type: 'at', data: { qq: String(userId) } },
        { type: 'text', data: { text: ' ' + reply } },
      ]
    : reply;

  const body = JSON.stringify({
    action: 'send_msg',
    params: type === 'group'
      ? { message_type: 'group', group_id: groupId, message: outMsg }
      : { message_type: 'private', user_id: userId, message: outMsg },
  });

  if (conn.readyState !== WebSocket.OPEN) {
    log('send failed: socket closed');
    return;
  }
  try {
    conn.send(body);
    log('replied [' + sessionKey + ']: ' + reply.slice(0, 40));
  } catch (e) {
    log('send error: ' + e.message);
  }
}

// ---------------------------------------------------------------- 启动
trimLog();
log('starting, port=' + PORT);

const wss = new WebSocket.Server({ host: '127.0.0.1', port: PORT });

wss.on('listening', () => log('listening on 127.0.0.1:' + PORT));

wss.on('connection', (conn, req) => {
  log('napcat connected from ' + (req.socket.remoteAddress || '?'));

  conn.on('message', (data) => {
    onEvent(data.toString(), conn).catch((e) => log('handle error: ' + e.message));
  });
  conn.on('close', () => log('napcat disconnected'));
  conn.on('error', (e) => log('ws error: ' + e.message));
});

wss.on('error', (e) => log('server error: ' + e.message));

// 防止进程被意外异常带走
process.on('uncaughtException', (e) => log('uncaught: ' + e.message));
process.on('unhandledRejection', (e) => log('unhandled: ' + e));
