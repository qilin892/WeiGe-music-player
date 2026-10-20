#!/usr/bin/env node
'use strict';
/**
 * 单独启动云端 MCP 桥（不想开整个播放器时用这个）。
 * 正式使用时通常由 server.js 自动拉起，见 lib/mcp.js。
 *
 *   node mcp-bridge.js --token eyJ...
 *   MCP_TOKEN=eyJ... node mcp-bridge.js
 *   node mcp-bridge.js            # 用上次保存在 mcp.json 里的 token
 */

const path = require('node:path');
const { createBridge, readMcpConfig, saveMcpConfig } = require('./lib/mcp.js');

const DATA_ROOT = process.env.DATA_ROOT || __dirname;

function argValue(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : null;
}

const fromArg = argValue('--token');
const token = fromArg || process.env.MCP_TOKEN || readMcpConfig(DATA_ROOT)?.token;

if (!token) {
  console.error('缺少 token：--token <接入点token> / MCP_TOKEN 环境变量 / mcp.json');
  process.exit(1);
}
if (fromArg || process.env.MCP_TOKEN) saveMcpConfig(DATA_ROOT, token);

console.log('伟哥音乐 MCP 桥（独立模式）');
const bridge = createBridge({ token, debug: process.argv.includes('--debug') });
bridge.start();

process.on('SIGINT', () => {
  console.log('\n收到退出信号，关闭连接');
  bridge.stop();
  process.exit(0);
});
