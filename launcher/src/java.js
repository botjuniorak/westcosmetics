'use strict';
// Pobiera Jave dla Minecrafta z oficjalnych serwerow Mojanga (ta sama, ktorej uzywa ich launcher).

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ALL = 'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json';

function platformKey() {
  if (process.platform === 'win32') return process.arch === 'arm64' ? 'windows-arm64' : 'windows-x64';
  if (process.platform === 'darwin') return process.arch === 'arm64' ? 'mac-os-arm64' : 'mac-os';
  return 'linux';
}

function sha1(file) {
  return crypto.createHash('sha1').update(fs.readFileSync(file)).digest('hex');
}

async function getJson(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error('Nie pobrano ' + url + ' (' + r.status + ')');
  return r.json();
}

async function download(url, target) {
  const r = await fetch(url);
  if (!r.ok) throw new Error('Nie pobrano ' + path.basename(target) + ' (' + r.status + ')');
  const buf = Buffer.from(await r.arrayBuffer());
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, buf);
}

/**
 * Zwraca sciezke do javaw/java. Pobiera tylko brakujace lub zmienione pliki.
 * component: np. "java-runtime-delta" (Java 21 dla 1.21.x).
 */
async function ensureJava(root, component, onProgress) {
  const dir = path.join(root, 'runtime', component);
  const exe = process.platform === 'win32' ? path.join(dir, 'bin', 'javaw.exe') : path.join(dir, 'bin', 'java');
  const marker = path.join(dir, '.atomic-ok');
  if (fs.existsSync(exe) && fs.existsSync(marker)) return exe;

  const all = await getJson(ALL);
  const list = (all[platformKey()] || {})[component];
  if (!list || !list.length) throw new Error('Brak Javy ' + component + ' dla tego systemu.');
  const manifest = await getJson(list[0].manifest.url);

  const files = Object.entries(manifest.files);
  const todo = [];
  for (const [rel, f] of files) {
    const target = path.join(dir, rel);
    if (f.type === 'directory') { fs.mkdirSync(target, { recursive: true }); continue; }
    if (f.type !== 'file') continue;
    if (fs.existsSync(target) && sha1(target) === f.downloads.raw.sha1) continue;
    todo.push({ url: f.downloads.raw.url, target, exec: f.executable });
  }

  let done = 0;
  const workers = Array.from({ length: 8 }, async () => {
    while (todo.length) {
      const job = todo.shift();
      await download(job.url, job.target);
      if (job.exec && process.platform !== 'win32') fs.chmodSync(job.target, 0o755);
      done++;
      if (onProgress) onProgress(done, done + todo.length);
    }
  });
  await Promise.all(workers);
  fs.writeFileSync(marker, new Date().toISOString());
  return exe;
}

module.exports = { ensureJava };
