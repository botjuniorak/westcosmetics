'use strict';
const $ = (id) => document.getElementById(id);
const api = window.atomic;
let state = { account: null, settings: null, loginReady: false, playing: false, server: null };

function toast(text, bad) {
  const t = $('toast');
  t.textContent = text;
  t.className = 'toast show' + (bad ? ' bad' : '');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.className = 'toast'; }, 4500);
}

function setStatus(text) { $('status').textContent = text; }
function setProgress(p) {
  const f = $('progressFill');
  if (p === null || p === undefined) { f.classList.add('indeterminate'); f.style.width = '35%'; }
  else { f.classList.remove('indeterminate'); f.style.width = Math.round(Math.max(0, Math.min(1, p)) * 100) + '%'; }
}

function renderAccount() {
  const a = state.account;
  if (a) {
    $('accName').textContent = a.name;
    $('btnAccount').textContent = 'Wyloguj';
    $('avatar').src = 'https://mc-heads.net/avatar/' + a.uuid + '/64';
  } else {
    $('accName').textContent = 'Nie zalogowano';
    $('btnAccount').textContent = state.loginReady ? 'Zaloguj kontem Microsoft' : 'Logowanie wkrótce';
    $('avatar').src = 'logo.png';
  }
  $('btnPlay').disabled = state.playing;
}

function renderSettings() {
  const s = state.settings;
  $('ram').value = s.ram;
  $('ramVal').textContent = s.ram + ' GB';
  $('perf').checked = s.performance;
  $('autoJoin').checked = s.autoJoin;
  $('keepOpen').checked = s.keepOpen;
}

async function saveSettings() {
  state.settings = await api.saveSettings({
    ram: $('ram').value,
    performance: $('perf').checked,
    autoJoin: $('autoJoin').checked,
    keepOpen: $('keepOpen').checked
  });
  renderSettings();
}

async function refreshServer() {
  const s = await api.serverStatus();
  $('serverDot').className = 'server-dot' + (s.online ? ' on' : '');
  $('serverSub').textContent = s.online ? `${s.players} graczy online` : 'offline';
}

// ------------------------------------------------------------ zdarzenia

document.querySelectorAll('.nav[data-page]').forEach((b) => b.addEventListener('click', () => {
  document.querySelectorAll('.nav[data-page]').forEach((x) => x.classList.toggle('active', x === b));
  document.querySelectorAll('.page').forEach((p) => p.classList.toggle('active', p.id === 'page-' + b.dataset.page));
}));

$('btnMin').onclick = () => api.minimize();
$('btnClose').onclick = () => api.close();
$('btnFolder').onclick = () => api.openFolder();
$('btnCopyIp').onclick = () => { navigator.clipboard.writeText(state.server.ip); toast('Skopiowano IP: ' + state.server.ip); };

$('ram').oninput = () => { $('ramVal').textContent = $('ram').value + ' GB'; };
$('ram').onchange = saveSettings;
['perf', 'autoJoin', 'keepOpen'].forEach((id) => { $(id).onchange = saveSettings; });

$('btnAccount').onclick = async () => {
  if (state.account) {
    state.account = await api.logout();
    renderAccount();
    return;
  }
  if (!state.loginReady) {
    toast('Logowanie ruszy, gdy Microsoft zatwierdzi launcher Atomic.', true);
    return;
  }
  try {
    $('loginCode').textContent = '········';
    $('loginModal').classList.add('show');
    state.account = await api.login();
    toast('Zalogowano jako ' + state.account.name);
  } catch (e) {
    toast(cleanErr(e), true);
  } finally {
    $('loginModal').classList.remove('show');
    renderAccount();
  }
};
$('btnCancelLogin').onclick = () => { api.cancelLogin(); $('loginModal').classList.remove('show'); };
$('loginUrl').onclick = (e) => { e.preventDefault(); api.open('https://www.microsoft.com/link'); };

$('btnPlay').onclick = async () => {
  if (!state.account) {
    toast(state.loginReady ? 'Najpierw zaloguj się kontem Microsoft.' : 'Logowanie ruszy po zatwierdzeniu launchera przez Microsoft.', true);
    return;
  }
  state.playing = true;
  $('btnPlay').textContent = 'URUCHAMIAM';
  renderAccount();
  setProgress(0);
  try {
    await api.play();
    $('btnPlay').textContent = 'W GRZE';
  } catch (e) {
    state.playing = false;
    $('btnPlay').textContent = 'GRAJ';
    setStatus('Błąd: ' + cleanErr(e));
    setProgress(0);
    toast(cleanErr(e), true);
    renderAccount();
  }
};

function cleanErr(e) {
  return String(e && e.message ? e.message : e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
}

api.on('status', setStatus);
api.on('progress', setProgress);
api.on('login:code', (c) => { $('loginCode').textContent = c.userCode; });
api.on('account', (a) => { state.account = a; renderAccount(); });
api.on('closed', () => {
  state.playing = false;
  $('btnPlay').textContent = 'GRAJ';
  setStatus('Gra zamknięta.');
  setProgress(0);
  renderAccount();
});

// ------------------------------------------------------------ start

(async () => {
  const d = await api.init();
  state = { ...state, ...d };
  $('mcVersion').textContent = d.minecraft;
  $('serverName').textContent = d.server.name;
  $('btnCopyIp').textContent = d.server.ip;
  $('autoJoinIp').textContent = d.server.ip;
  $('appVersion').textContent = 'Atomic ' + d.version;
  renderSettings();
  renderAccount();
  if (!d.loginReady) setStatus('Launcher czeka na zgodę Microsoftu na logowanie.');
  refreshServer();
  setInterval(refreshServer, 60000);
})();
