#!/usr/bin/env node
/**
 * 启动 QQ 之前把 OneBot 反向连接配置写好。
 *
 * 为什么必须在启动前：NapCat 只在启动时读 onebot11*.json，
 * 启动后再改文件它不会重新加载（websocketClients 虽然会轮询，
 * 但首次启动时如果文件里压根没有这一项，就没有"变化"可检测）。
 *
 * 这一步对应 AstrMobile 的 start.sh 里那段 Python 补丁。
 */

const fs = require('fs');
const path = require('path');

const CFG_DIR = '/app/napcat/config';
const PORT = 3001;

function log(msg) {
  try { fs.appendFileSync('/app/bridge.log', '[setup] ' + msg + '\n'); } catch (e) {}
  console.log('[setup] ' + msg);
}

function patchFile(file) {
  let root;
  try {
    root = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    root = {};
  }

  const network = root.network || (root.network = {});
  const clients = network.websocketClients || (network.websocketClients = []);

  // 清掉旧版留下的同名项，避免重复
  const kept = clients.filter((c) => c && c.name !== 'cielo-bridge');
  kept.push({
    name: 'cielo-bridge',
    enable: true,
    url: 'ws://127.0.0.1:' + PORT,
    token: '',
    messagePostFormat: 'array',
    reportSelfMessage: false,
    debug: false,
    heartInterval: 30000,
    reconnectInterval: 3000,
  });

  network.websocketClients = kept;

  // 旧版用的是 websocketServers（NapCat 自己监听 3001），会和桥抢端口，删掉
  if (Array.isArray(network.websocketServers)) {
    network.websocketServers = network.websocketServers.filter(
      (s) => !s || s.name !== 'cielo-ws');
  }

  fs.writeFileSync(file, JSON.stringify(root, null, 2));
  return true;
}

function main() {
  if (!fs.existsSync(CFG_DIR)) {
    log('config dir missing: ' + CFG_DIR);
    return;
  }

  let files = fs.readdirSync(CFG_DIR)
    .filter((f) => f.startsWith('onebot11') && f.endsWith('.json'));

  // 还没登录过：创建默认模板，NapCat 登录后会继承这个配置
  if (files.length === 0) {
    files = ['onebot11.json'];
    log('no onebot11 found, creating default template');
  }

  files.forEach((name) => {
    const full = path.join(CFG_DIR, name);
    try {
      patchFile(full);
      log('patched ' + name);
    } catch (e) {
      log('failed ' + name + ': ' + e.message);
    }
  });
}

main();
