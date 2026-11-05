'use strict';
const { app, BrowserWindow, ipcMain, shell, safeStorage } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const auth = require('./src/auth');
const game = require('./src/game');

const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8'));
const ROOT = process.platform === 'win32'
  ? path.join(process.env.APPDATA || os.homedir(), cfg.folder)
  : path.join(os.homedir(), cfg.folder);
const DATA = app.getPath('userData');
const ACCOUNT_FILE = path.join(DATA, 'account.dat');
const SETTINGS_FILE = path.join(DATA, 'settings.json');

let win = null;
let account = null;
let loginCancelled = false;
let running = null;

// ------------------------------------------------------------ zapis konta i ustawien

function saveAccount(acc) {
  fs.mkdirSync(DATA, { recursive: true });
  const raw = JSON.stringify(acc);
  const data = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(raw) : Buffer.from(raw, 'utf8');
  fs.writeFileSync(ACCOUNT_FILE, data);
}

function loadAccount() {
  try {
    const buf = fs.readFileSync(ACCOUNT_FILE);
    const raw = safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(buf) : buf.toString('utf8');
    return JSON.parse(raw);
  } catch { return null; }
}

const DEFAULTS = { ram: 4, performance: true, autoJoin: false, keepOpen: false };
function loadSettings() {
  try { return { ...DEFAULTS, ...JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')) }; } catch { return { ...DEFAULTS }; }
}
function saveSettings(s) {
  fs.mkdirSync(DATA, { recursive: true });
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(s, null, 2));
}

const send = (ch, payload) => { if (win && !win.isDestroyed()) win.webContents.send(ch, payload); };
const publicAccount = (a) => (a ? { name: a.name, uuid: a.uuid } : null);

// ------------------------------------------------------------ okno

function createWindow() {
  win = new BrowserWindow({
    width: 1000,
    height: 620,
    minWidth: 900,
    minHeight: 560,
    frame: false,
    backgroundColor: '#0c0a1c',
    title: 'Atomic',
    icon: path.join(__dirname, 'renderer', 'logo.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
}

app.whenReady().then(() => {
  account = loadAccount();
  createWindow();
});
app.on('window-all-closed', () => app.quit());

// ------------------------------------------------------------ IPC

ipcMain.handle('win:minimize', () => win.minimize());
ipcMain.handle('win:close', () => win.close());
ipcMain.handle('open', (_e, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });

ipcMain.handle('init', () => ({
  account: publicAccount(account),
  settings: loadSettings(),
  server: cfg.server,
  minecraft: cfg.minecraft,
  loginReady: !!cfg.clientId,
  version: app.getVersion()
}));

ipcMain.handle('settings:save', (_e, s) => {
  const clean = {
    ram: Math.max(2, Math.min(16, parseInt(s.ram, 10) || 4)),
    performance: !!s.performance,
    autoJoin: !!s.autoJoin,
    keepOpen: !!s.keepOpen
  };
  saveSettings(clean);
  return clean;
});

ipcMain.handle('login', async () => {
  if (!cfg.clientId) throw new Error('Logowanie będzie dostępne po zatwierdzeniu launchera przez Microsoft.');
  loginCancelled = false;
  const acc = await auth.loginDeviceCode(cfg.clientId, (c) => {
    send('login:code', c);
    shell.openExternal(c.url);
  }, () => loginCancelled);
  account = acc;
  saveAccount(acc);
  return publicAccount(acc);
});
ipcMain.handle('login:cancel', () => { loginCancelled = true; });

ipcMain.handle('logout', () => {
  account = null;
  try { fs.unlinkSync(ACCOUNT_FILE); } catch { /* brak pliku */ }
  return null;
});

ipcMain.handle('server:status', async () => {
  try {
    const r = await fetch('https://api.mcsrvstat.us/3/' + encodeURIComponent(cfg.server.ip), {
      headers: { 'User-Agent': 'Atomic-Launcher/1.0' }
    });
    const d = await r.json();
    return { online: !!d.online, players: d.players ? d.players.online : 0, max: d.players ? d.players.max : 0 };
  } catch { return { online: false, players: 0, max: 0 }; }
});

ipcMain.handle('open:folder', () => { fs.mkdirSync(ROOT, { recursive: true }); shell.openPath(ROOT); });

ipcMain.handle('play', async () => {
  if (running) throw new Error('Gra już działa.');
  if (!account) throw new Error('Najpierw zaloguj się kontem Microsoft.');
  const settings = loadSettings();

  // odswiez token, jesli wygasa w ciagu godziny
  if (!account.expiresAt || account.expiresAt - Date.now() < 3600 * 1000) {
    send('status', 'Odświeżam sesję...');
    try {
      account = await auth.refresh(cfg.clientId, account.refreshToken);
      saveAccount(account);
    } catch (e) {
      account = null;
      try { fs.unlinkSync(ACCOUNT_FILE); } catch { /* */ }
      send('account', null);
      throw e;
    }
  }

  const logFile = path.join(ROOT, 'atomic-launcher.log');
  fs.mkdirSync(ROOT, { recursive: true });
  const log = fs.createWriteStream(logFile, { flags: 'w' });

  running = await game.play(ROOT, cfg, settings, account, {
    status: (t) => send('status', t),
    progress: (p) => send('progress', p),
    log: (l) => log.write(l.endsWith('\n') ? l : l + '\n'),
    closed: (code) => {
      running = null;
      log.end();
      send('closed', code);
      if (win && !win.isDestroyed()) { win.show(); win.restore(); }
    }
  });
  if (!settings.keepOpen && win) win.minimize();
  return true;
});
