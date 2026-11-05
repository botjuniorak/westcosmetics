'use strict';
// Logowanie kontem Microsoft (Minecraft premium) - "device code flow":
// gracz wpisuje kod na microsoft.com/link, launcher dostaje token.
// Wymaga wlasnej aplikacji w Azure (clientId) zatwierdzonej do Minecraft API.

const SCOPE = 'XboxLive.signin offline_access';
const MS = 'https://login.microsoftonline.com/consumers/oauth2/v2.0';

async function post(url, body, json = false) {
  const res = await fetch(url, {
    method: 'POST',
    headers: json
      ? { 'Content-Type': 'application/json', Accept: 'application/json' }
      : { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: json ? JSON.stringify(body) : new URLSearchParams(body).toString()
  });
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  return { ok: res.ok, status: res.status, data };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Krok 1: kod do wpisania. onCode({userCode, url}) wywolywane od razu. */
async function loginDeviceCode(clientId, onCode, isCancelled) {
  const dc = await post(`${MS}/devicecode`, { client_id: clientId, scope: SCOPE });
  if (!dc.ok) throw new Error('Microsoft odrzucił logowanie (' + (dc.data.error_description || dc.status) + ')');
  onCode({ userCode: dc.data.user_code, url: dc.data.verification_uri });

  const deadline = Date.now() + dc.data.expires_in * 1000;
  let interval = (dc.data.interval || 5) * 1000;
  while (Date.now() < deadline) {
    if (isCancelled && isCancelled()) throw new Error('Anulowano logowanie.');
    await sleep(interval);
    const t = await post(`${MS}/token`, {
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      client_id: clientId,
      device_code: dc.data.device_code
    });
    if (t.ok) return minecraftFromMs(t.data.access_token, t.data.refresh_token);
    const err = t.data.error;
    if (err === 'authorization_pending') continue;
    if (err === 'slow_down') { interval += 5000; continue; }
    if (err === 'expired_token') break;
    throw new Error('Logowanie nie powiodło się (' + (t.data.error_description || err || t.status) + ')');
  }
  throw new Error('Kod wygasł. Spróbuj zalogować się ponownie.');
}

/** Odswiezenie sesji z zapisanego refresh tokena (bez ponownego wpisywania kodu). */
async function refresh(clientId, refreshToken) {
  const t = await post(`${MS}/token`, {
    grant_type: 'refresh_token',
    client_id: clientId,
    refresh_token: refreshToken,
    scope: SCOPE
  });
  if (!t.ok) throw new Error('Sesja wygasła - zaloguj się ponownie.');
  return minecraftFromMs(t.data.access_token, t.data.refresh_token || refreshToken);
}

async function minecraftFromMs(msToken, refreshToken) {
  // Xbox Live
  const xbl = await post('https://user.auth.xboxlive.com/user/authenticate', {
    Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: 'd=' + msToken },
    RelyingParty: 'http://auth.xboxlive.com',
    TokenType: 'JWT'
  }, true);
  if (!xbl.ok) throw new Error('Błąd Xbox Live (' + xbl.status + ')');
  const uhs = xbl.data.DisplayClaims.xui[0].uhs;

  // XSTS
  const xsts = await post('https://xsts.auth.xboxlive.com/xsts/authorize', {
    Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.data.Token] },
    RelyingParty: 'rp://api.minecraftservices.com/',
    TokenType: 'JWT'
  }, true);
  if (!xsts.ok) {
    const code = xsts.data && xsts.data.XErr;
    if (code === 2148916233) throw new Error('To konto Microsoft nie ma profilu Xbox. Zaloguj się raz na minecraft.net.');
    if (code === 2148916238) throw new Error('Konto dziecka - musi zostać dodane do rodziny przez dorosłego.');
    throw new Error('Błąd XSTS (' + (code || xsts.status) + ')');
  }

  // Minecraft
  const mc = await post('https://api.minecraftservices.com/authentication/login_with_xbox', {
    identityToken: `XBL3.0 x=${uhs};${xsts.data.Token}`
  }, true);
  if (mc.status === 403) {
    throw new Error('Launcher czeka jeszcze na zgodę Microsoftu na logowanie (wniosek aka.ms/mce-reviewappid).');
  }
  if (!mc.ok) throw new Error('Błąd logowania Minecraft (' + mc.status + ')');

  const prof = await fetch('https://api.minecraftservices.com/minecraft/profile', {
    headers: { Authorization: 'Bearer ' + mc.data.access_token }
  });
  if (prof.status === 404) throw new Error('To konto nie ma kupionego Minecrafta (Java Edition).');
  if (!prof.ok) throw new Error('Nie pobrano profilu (' + prof.status + ')');
  const p = await prof.json();

  return {
    name: p.name,
    uuid: p.id,
    accessToken: mc.data.access_token,
    expiresAt: Date.now() + (mc.data.expires_in || 86400) * 1000,
    refreshToken
  };
}

module.exports = { loginDeviceCode, refresh };
