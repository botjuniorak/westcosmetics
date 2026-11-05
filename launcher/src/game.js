'use strict';
// Przygotowanie i start gry: Fabric + mody (Fabric API, WestCosmetics, opcjonalnie Sodium/Iris) + Java.

const fs = require('fs');
const path = require('path');
const { Client } = require('minecraft-launcher-core');
const { ensureJava } = require('./java');

const UA = { 'User-Agent': 'Atomic-Launcher/1.0 (westmc.eu)' };

async function getJson(url) {
  const r = await fetch(url, { headers: UA });
  if (!r.ok) throw new Error('Serwer ' + new URL(url).host + ' odpowiedział ' + r.status);
  return r.json();
}

async function download(url, target) {
  const r = await fetch(url, { headers: UA });
  if (!r.ok) throw new Error('Nie pobrano ' + path.basename(target) + ' (' + r.status + ')');
  const buf = Buffer.from(await r.arrayBuffer());
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target + '.part', buf);
  fs.renameSync(target + '.part', target);
}

/** Fabric Loader: zapisuje versions/<id>/<id>.json, zwraca id wersji. */
async function ensureFabric(root, mc) {
  const loaders = await getJson(`https://meta.fabricmc.net/v2/versions/loader/${mc}`);
  const pick = loaders.find((l) => l.loader.stable) || loaders[0];
  const id = `fabric-loader-${pick.loader.version}-${mc}`;
  const dir = path.join(root, 'versions', id);
  const file = path.join(dir, id + '.json');
  if (!fs.existsSync(file)) {
    const profile = await getJson(`https://meta.fabricmc.net/v2/versions/loader/${mc}/${pick.loader.version}/profile/json`);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(profile, null, 2));
  }
  return id;
}

async function modrinthLatest(slug, mc) {
  const q = `loaders=${encodeURIComponent('["fabric"]')}&game_versions=${encodeURIComponent(`["${mc}"]`)}`;
  const versions = await getJson(`https://api.modrinth.com/v2/project/${slug}/version?${q}`);
  if (!versions.length) throw new Error(`Brak ${slug} dla ${mc}`);
  const v = versions.find((x) => x.version_type === 'release') || versions[0];
  const f = v.files.find((x) => x.primary) || v.files[0];
  return { name: f.filename, url: f.url };
}

async function cosmeticsLatest(repo) {
  const rel = await getJson(`https://api.github.com/repos/${repo}/releases/latest`);
  const jars = rel.assets
    .filter((a) => a.name.endsWith('.jar') && !a.name.includes('sources'))
    .sort((a, b) => (b.updated_at || '').localeCompare(a.updated_at || ''));
  if (!jars.length) throw new Error('Brak pliku kosmetyków w wydaniu.');
  return { name: jars[0].name, url: jars[0].browser_download_url };
}

/** Uzgadnia folder mods z lista "naszych" modow. Mody dodane recznie przez gracza zostaja. */
async function syncMods(root, cfg, settings, status) {
  const modsDir = path.join(root, 'mods');
  fs.mkdirSync(modsDir, { recursive: true });
  const statePath = path.join(root, 'atomic-mods.json');
  let managed = [];
  try { managed = JSON.parse(fs.readFileSync(statePath, 'utf8')); } catch { /* pierwszy start */ }

  const wanted = [];
  status('Sprawdzam mody...');
  wanted.push(await modrinthLatest('fabric-api', cfg.minecraft));
  try { wanted.push(await cosmeticsLatest(cfg.cosmeticsRepo)); }
  catch (e) { status('Kosmetyki niedostępne: ' + e.message); }
  if (settings.performance) {
    wanted.push(await modrinthLatest('sodium', cfg.minecraft));
    wanted.push(await modrinthLatest('iris', cfg.minecraft));
  }

  const wantedNames = new Set(wanted.map((w) => w.name));
  for (const old of managed) {
    if (!wantedNames.has(old)) {
      const p = path.join(modsDir, old);
      if (fs.existsSync(p)) fs.unlinkSync(p);
    }
  }
  for (const w of wanted) {
    const target = path.join(modsDir, w.name);
    if (!fs.existsSync(target)) {
      status('Pobieram ' + w.name + '...');
      await download(w.url, target);
    }
  }
  fs.writeFileSync(statePath, JSON.stringify([...wantedNames], null, 2));
}

/** servers.dat z serwerem (NBT bez kompresji), tylko jesli gracz nie ma jeszcze listy. */
function ensureServers(root, server) {
  const file = path.join(root, 'servers.dat');
  if (fs.existsSync(file)) return;
  const parts = [];
  const byte = (b) => parts.push(Buffer.from([b]));
  const int = (v) => { const b = Buffer.alloc(4); b.writeInt32BE(v); parts.push(b); };
  const str = (s) => { const d = Buffer.from(s, 'utf8'); const l = Buffer.alloc(2); l.writeUInt16BE(d.length); parts.push(l, d); };
  byte(10); str('');
  byte(9); str('servers'); byte(10); int(1);
  byte(8); str('ip'); str(server.ip);
  byte(8); str('name'); str(server.name);
  byte(0);
  byte(0);
  fs.mkdirSync(root, { recursive: true });
  fs.writeFileSync(file, Buffer.concat(parts));
}

/**
 * Pelny start. events: status(text), progress(fraction 0..1 lub null), log(line), closed(code)
 */
async function play(root, cfg, settings, account, events) {
  const status = (t) => events.status(t);
  fs.mkdirSync(root, { recursive: true });

  status('Przygotowuję Fabric...');
  const versionId = await ensureFabric(root, cfg.minecraft);

  await syncMods(root, cfg, settings, status);
  ensureServers(root, cfg.server);

  status('Sprawdzam Javę...');
  const javaPath = await ensureJava(root, 'java-runtime-delta', (d, t) => {
    status(`Pobieram Javę (${d}/${t})...`);
    events.progress(t ? d / t : null);
  });

  status('Pobieram pliki gry...');
  events.progress(null);
  const client = new Client();
  client.on('progress', (e) => {
    if (e && e.total) {
      events.progress(e.task / e.total);
      status(`Pobieram pliki gry: ${e.type} (${e.task}/${e.total})`);
    }
  });
  client.on('debug', (l) => events.log(String(l)));
  client.on('data', (l) => {
    const line = String(l);
    events.log(line);
    if (line.includes('Setting user:') || line.includes('Backend library')) status('Gra się uruchamia...');
  });
  client.on('close', (code) => events.closed(code));

  const gameArgs = [];
  if (settings.autoJoin) gameArgs.push('--quickPlayMultiplayer', cfg.server.ip);

  const proc = await client.launch({
    root,
    javaPath,
    version: { number: cfg.minecraft, type: 'release', custom: versionId },
    memory: { max: settings.ram + 'G', min: Math.min(2, settings.ram) + 'G' },
    authorization: {
      access_token: account.accessToken,
      client_token: account.uuid,
      uuid: account.uuid,
      name: account.name,
      user_properties: '{}',
      meta: { type: 'msa', demo: false }
    },
    customLaunchArgs: gameArgs,
    customArgs: ['-XX:+UseG1GC', '-XX:+UnlockExperimentalVMOptions', '-XX:G1NewSizePercent=20', '-XX:MaxGCPauseMillis=50'],
    overrides: { detached: false }
  });
  if (!proc) throw new Error('Gra nie wystartowała - sprawdź logi.');
  status('Gra uruchomiona. Miłej gry!');
  events.progress(1);
  return proc;
}

module.exports = { play };
