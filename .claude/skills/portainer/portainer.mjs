#!/usr/bin/env node
// Portainer control skill — REST API client.
// Credenciais carregadas de ~/.claude/secrets/portainer.env (ou variáveis de ambiente).
// Runtime: Node 18+ (fetch nativo).

import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

// ---------- carregar .env ----------
function loadEnv() {
  const path = join(homedir(), ".claude", "secrets", "portainer.env");
  if (existsSync(path)) {
    for (const raw of readFileSync(path, "utf8").split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith("#")) continue;
      const eq = line.indexOf("=");
      if (eq === -1) continue;
      const key = line.slice(0, eq).trim();
      let val = line.slice(eq + 1).trim();
      if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
        val = val.slice(1, -1);
      }
      if (!(key in process.env)) process.env[key] = val;
    }
  }
}
loadEnv();

const URL = (process.env.PORTAINER_URL || "").replace(/\/+$/, "");
const TOKEN = process.env.PORTAINER_TOKEN || "";
const USER = process.env.PORTAINER_USER || "";
const PASS = process.env.PORTAINER_PASS || "";

if (!URL) {
  console.error("Erro: PORTAINER_URL não configurado (~/.claude/secrets/portainer.env).");
  process.exit(1);
}

// ---------- parse de argumentos ----------
const argv = process.argv.slice(2);
const cmd = argv[0];
const positional = [];
const flags = {};
for (let i = 1; i < argv.length; i++) {
  const a = argv[i];
  if (a.startsWith("--")) {
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith("--")) {
      flags[key] = true;
    } else {
      flags[key] = next;
      i++;
    }
  } else {
    positional.push(a);
  }
}
const asJson = !!flags.json;

// ---------- auth ----------
let jwt = null;
async function authHeaders() {
  if (TOKEN) return { "X-API-Key": TOKEN };
  if (jwt) return { Authorization: `Bearer ${jwt}` };
  if (USER && PASS) {
    const r = await fetch(`${URL}/api/auth`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: USER, password: PASS }),
    });
    if (!r.ok) {
      console.error(`Falha no login (HTTP ${r.status}): ${await r.text()}`);
      process.exit(1);
    }
    jwt = (await r.json()).jwt;
    return { Authorization: `Bearer ${jwt}` };
  }
  console.error("Erro: configure PORTAINER_TOKEN ou PORTAINER_USER/PORTAINER_PASS.");
  process.exit(1);
}

async function api(path, opts = {}) {
  const headers = { ...(await authHeaders()), ...(opts.headers || {}) };
  const r = await fetch(`${URL}${path}`, { ...opts, headers });
  const text = await r.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!r.ok) {
    const msg = data && data.message ? data.message : (typeof data === "string" ? data : JSON.stringify(data));
    throw new Error(`HTTP ${r.status} em ${path}: ${msg}`);
  }
  return data;
}

// ---------- resolução de endpoint ----------
let endpointsCache = null;
async function getEndpoints() {
  if (!endpointsCache) endpointsCache = await api("/api/endpoints");
  return endpointsCache;
}
async function resolveEndpoint() {
  const override = flags.endpoint || process.env.PORTAINER_ENDPOINT;
  const eps = await getEndpoints();
  if (override) {
    const ep = eps.find((e) => String(e.Id) === String(override) || e.Name === override);
    if (!ep) throw new Error(`Endpoint "${override}" não encontrado.`);
    return ep.Id;
  }
  if (eps.length === 0) throw new Error("Nenhum endpoint disponível no Portainer.");
  return eps[0].Id;
}

// proxy Docker para um endpoint
async function docker(epId, path, opts = {}) {
  return api(`/api/endpoints/${epId}/docker${path}`, opts);
}

// ---------- helpers de exibição ----------
function out(obj) {
  console.log(JSON.stringify(obj, null, 2));
}
function pad(s, n) {
  s = String(s ?? "");
  return s.length >= n ? s.slice(0, n) : s + " ".repeat(n - s.length);
}

// localizar container por nome / id / prefixo
async function findContainer(epId, ref) {
  const list = await docker(epId, "/containers/json?all=1");
  const r = ref.toLowerCase();
  const match = list.find((c) => {
    const names = (c.Names || []).map((n) => n.replace(/^\//, "").toLowerCase());
    return (
      c.Id.toLowerCase() === r ||
      c.Id.toLowerCase().startsWith(r) ||
      names.includes(r)
    );
  });
  if (!match) throw new Error(`Container "${ref}" não encontrado.`);
  return match;
}

// localizar stack por id ou nome
async function findStack(ref) {
  const stacks = await api("/api/stacks");
  const r = String(ref).toLowerCase();
  const match = stacks.find((s) => String(s.Id) === String(ref) || (s.Name || "").toLowerCase() === r);
  if (!match) throw new Error(`Stack "${ref}" não encontrada.`);
  return match;
}

// ---------- comandos ----------
async function main() {
  switch (cmd) {
    case "status": {
      const st = await api("/api/status");
      const eps = await getEndpoints();
      if (asJson) return out({ status: st, endpoints: eps });
      console.log(`Portainer ${st.Version}`);
      console.log(`URL: ${URL}`);
      console.log(`\nEndpoints (${eps.length}):`);
      for (const e of eps) {
        console.log(`  [${pad(e.Id, 3)}] ${pad(e.Name, 24)} type=${e.Type} status=${e.Status === 1 ? "up" : "down"}`);
      }
      break;
    }

    case "endpoints": {
      const eps = await getEndpoints();
      if (asJson) return out(eps);
      for (const e of eps) {
        console.log(`[${pad(e.Id, 3)}] ${pad(e.Name, 24)} type=${e.Type} status=${e.Status === 1 ? "up" : "down"} url=${e.URL || "local"}`);
      }
      break;
    }

    case "ps": {
      const epId = await resolveEndpoint();
      const all = flags.all ? "1" : "0";
      const list = await docker(epId, `/containers/json?all=${all}`);
      if (asJson) return out(list);
      console.log(`${pad("NAME", 28)} ${pad("STATE", 10)} ${pad("STATUS", 24)} IMAGE`);
      for (const c of list) {
        const name = (c.Names || [])[0]?.replace(/^\//, "") || c.Id.slice(0, 12);
        console.log(`${pad(name, 28)} ${pad(c.State, 10)} ${pad(c.Status, 24)} ${c.Image}`);
      }
      break;
    }

    case "start":
    case "stop":
    case "restart":
    case "kill": {
      const ref = positional[0];
      if (!ref) throw new Error(`Uso: ${cmd} <id|nome>`);
      const epId = await resolveEndpoint();
      const c = await findContainer(epId, ref);
      const name = (c.Names || [])[0]?.replace(/^\//, "") || c.Id.slice(0, 12);
      await docker(epId, `/containers/${c.Id}/${cmd}`, { method: "POST" });
      console.log(`OK: ${cmd} -> ${name}`);
      break;
    }

    case "rm": {
      const ref = positional[0];
      if (!ref) throw new Error("Uso: rm <id|nome> [--force]");
      const epId = await resolveEndpoint();
      const c = await findContainer(epId, ref);
      const name = (c.Names || [])[0]?.replace(/^\//, "") || c.Id.slice(0, 12);
      const force = flags.force ? "?force=true" : "";
      await docker(epId, `/containers/${c.Id}${force}`, { method: "DELETE" });
      console.log(`OK: removido -> ${name}`);
      break;
    }

    case "logs": {
      const ref = positional[0];
      if (!ref) throw new Error("Uso: logs <id|nome> [--tail N]");
      const epId = await resolveEndpoint();
      const c = await findContainer(epId, ref);
      const tail = flags.tail || "200";
      // logs vêm como stream multiplexado; pedimos stdout+stderr e limpamos cabeçalhos de frame
      const headers = await authHeaders();
      const r = await fetch(`${URL}/api/endpoints/${epId}/docker/containers/${c.Id}/logs?stdout=1&stderr=1&tail=${tail}&timestamps=0`, { headers });
      if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`);
      const buf = Buffer.from(await r.arrayBuffer());
      process.stdout.write(demuxDockerLogs(buf));
      break;
    }

    case "exec": {
      // exec <id|nome> -- <comando...>   (tudo após "--" é o comando)
      // ou:  exec <id|nome> "<comando shell>"   (roda via sh -c)
      const ref = positional[0];
      if (!ref) throw new Error('Uso: exec <id|nome> -- <comando...>   |   exec <id|nome> "<cmd shell>"   [--user <u>] [--workdir <dir>]');
      const epId = await resolveEndpoint();
      const c = await findContainer(epId, ref);
      const name = (c.Names || [])[0]?.replace(/^\//, "") || c.Id.slice(0, 12);

      // monta o Cmd: tudo após "--" no argv original é tratado como argv literal;
      // sem "--", o restante dos posicionais vira `sh -c "<cmd>"`.
      const dashIdx = argv.indexOf("--");
      let cmdArr;
      if (dashIdx !== -1) {
        cmdArr = argv.slice(dashIdx + 1);
      } else {
        const shellCmd = positional.slice(1).join(" ");
        if (!shellCmd) throw new Error("Informe o comando: exec <id|nome> -- <comando...>");
        cmdArr = ["sh", "-c", shellCmd];
      }

      const body = {
        AttachStdout: true,
        AttachStderr: true,
        Tty: false,
        Cmd: cmdArr,
      };
      if (flags.user && flags.user !== true) body.User = String(flags.user);
      if (flags.workdir && flags.workdir !== true) body.WorkingDir = String(flags.workdir);

      const headers = await authHeaders();
      // 1) cria o exec
      const createRes = await fetch(`${URL}/api/endpoints/${epId}/docker/containers/${c.Id}/exec`, {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!createRes.ok) throw new Error(`exec create HTTP ${createRes.status}: ${await createRes.text()}`);
      const execId = (await createRes.json()).Id;

      // 2) inicia (síncrono: a resposta só fecha quando o comando termina)
      const startRes = await fetch(`${URL}/api/endpoints/${epId}/docker/exec/${execId}/start`, {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify({ Detach: false, Tty: false }),
      });
      if (!startRes.ok) throw new Error(`exec start HTTP ${startRes.status}: ${await startRes.text()}`);
      const buf = Buffer.from(await startRes.arrayBuffer());
      process.stdout.write(demuxDockerLogs(buf));

      // 3) reporta o exit code do comando (stderr para não poluir o stdout do output)
      try {
        const insp = await docker(epId, `/exec/${execId}/json`);
        console.error(`\n[${name}] exit code: ${insp.ExitCode}`);
        if (insp.ExitCode) process.exitCode = insp.ExitCode;
      } catch { /* ignore */ }
      break;
    }

    case "inspect": {
      const ref = positional[0];
      if (!ref) throw new Error("Uso: inspect <id|nome>");
      const epId = await resolveEndpoint();
      const c = await findContainer(epId, ref);
      const data = await docker(epId, `/containers/${c.Id}/json`);
      out(data);
      break;
    }

    case "stacks": {
      const stacks = await api("/api/stacks");
      if (asJson) return out(stacks);
      console.log(`${pad("ID", 5)} ${pad("NAME", 28)} ${pad("TYPE", 8)} ${pad("STATUS", 8)} ENDPOINT`);
      for (const s of stacks) {
        const type = s.Type === 1 ? "swarm" : s.Type === 2 ? "compose" : String(s.Type);
        const status = s.Status === 1 ? "active" : "inactive";
        console.log(`${pad(s.Id, 5)} ${pad(s.Name, 28)} ${pad(type, 8)} ${pad(status, 8)} ${s.EndpointId}`);
      }
      break;
    }

    case "stack-get": {
      const ref = positional[0];
      if (!ref) throw new Error("Uso: stack-get <id|nome>");
      const s = await findStack(ref);
      let file = null;
      try { file = (await api(`/api/stacks/${s.Id}/file`)).StackFileContent; } catch { /* alguns tipos não expõem */ }
      if (asJson) return out({ stack: s, file });
      out(s);
      if (file) {
        console.log("\n----- compose -----\n");
        console.log(file);
      }
      break;
    }

    case "stack-create": {
      // cria stack standalone (compose) a partir de um arquivo
      const name = flags.name || positional[0];
      if (!name) throw new Error("Uso: stack-create --name <nome> --file ./compose.yml [--endpoint <id>] [--env K=V,K2=V2]");
      if (!flags.file || flags.file === true) throw new Error("Informe --file <caminho do compose>");
      const epId = await resolveEndpoint();
      const content = readFileSync(flags.file, "utf8");
      const env = [];
      if (flags.env && flags.env !== true) {
        for (const pair of String(flags.env).split(",")) {
          const i = pair.indexOf("=");
          if (i > 0) env.push({ name: pair.slice(0, i).trim(), value: pair.slice(i + 1).trim() });
        }
      }
      const data = await api(`/api/stacks/create/standalone/string?endpointId=${epId}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, stackFileContent: content, env }),
      });
      console.log(`OK: stack criada -> ${data.Name} (id ${data.Id}) no endpoint ${epId}`);
      break;
    }

    case "stack-start":
    case "stack-stop": {
      const ref = positional[0];
      if (!ref) throw new Error(`Uso: ${cmd} <id|nome>`);
      const s = await findStack(ref);
      const action = cmd === "stack-start" ? "start" : "stop";
      await api(`/api/stacks/${s.Id}/${action}?endpointId=${s.EndpointId}`, { method: "POST" });
      console.log(`OK: ${action} stack -> ${s.Name}`);
      break;
    }

    case "stack-update": {
      const ref = positional[0];
      if (!ref) throw new Error("Uso: stack-update <id|nome> --file ./docker-compose.yml");
      if (!flags.file || flags.file === true) throw new Error("Informe --file <caminho do compose>");
      const s = await findStack(ref);
      const content = readFileSync(flags.file, "utf8");
      // recupera env vars atuais para não perdê-las no update
      let env = [];
      try { env = (await api(`/api/stacks/${s.Id}`)).Env || []; } catch { /* ignore */ }
      await api(`/api/stacks/${s.Id}?endpointId=${s.EndpointId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stackFileContent: content, env, prune: false, pullImage: false }),
      });
      console.log(`OK: stack atualizada -> ${s.Name}`);
      break;
    }

    case "stack-rm": {
      const ref = positional[0];
      if (!ref) throw new Error("Uso: stack-rm <id|nome>");
      const s = await findStack(ref);
      await api(`/api/stacks/${s.Id}?endpointId=${s.EndpointId}`, { method: "DELETE" });
      console.log(`OK: stack removida -> ${s.Name}`);
      break;
    }

    case "images": {
      const epId = await resolveEndpoint();
      const list = await docker(epId, "/images/json");
      if (asJson) return out(list);
      console.log(`${pad("REPOSITORY:TAG", 44)} ${pad("SIZE", 12)} ID`);
      for (const im of list) {
        const tag = (im.RepoTags || ["<none>:<none>"])[0];
        const size = (im.Size / 1048576).toFixed(1) + "MB";
        console.log(`${pad(tag, 44)} ${pad(size, 12)} ${im.Id.replace("sha256:", "").slice(0, 12)}`);
      }
      break;
    }

    case "image-pull": {
      const img = positional[0];
      if (!img) throw new Error("Uso: image-pull <imagem:tag>");
      const epId = await resolveEndpoint();
      const fromImage = encodeURIComponent(img);
      await docker(epId, `/images/create?fromImage=${fromImage}`, { method: "POST" });
      console.log(`OK: pull -> ${img}`);
      break;
    }

    case "volumes": {
      const epId = await resolveEndpoint();
      const data = await docker(epId, "/volumes");
      if (asJson) return out(data);
      for (const v of data.Volumes || []) {
        console.log(`${pad(v.Name, 40)} driver=${v.Driver} mount=${v.Mountpoint}`);
      }
      break;
    }

    case "networks": {
      const epId = await resolveEndpoint();
      const list = await docker(epId, "/networks");
      if (asJson) return out(list);
      console.log(`${pad("NAME", 28)} ${pad("DRIVER", 10)} ${pad("SCOPE", 8)} ID`);
      for (const n of list) {
        console.log(`${pad(n.Name, 28)} ${pad(n.Driver, 10)} ${pad(n.Scope, 8)} ${n.Id.slice(0, 12)}`);
      }
      break;
    }

    case "shell": {
      // shell interativo via WebSocket do Portainer (mesmo canal do Console da UI).
      // Liga o stdin/stdout DESTE terminal ao container — uso humano, não automatizável.
      const ref = positional[0];
      if (!ref) throw new Error('Uso: shell <id|nome> [--cmd "<shell>"] [--user <u>]   (sai com "exit" ou Ctrl+D)');
      const epId = await resolveEndpoint();
      const c = await findContainer(epId, ref);
      const name = (c.Names || [])[0]?.replace(/^\//, "") || c.Id.slice(0, 12);

      // comando a rodar dentro do container (default: tenta bash, cai pra sh)
      const inner = flags.cmd && flags.cmd !== true
        ? String(flags.cmd)
        : "([ -x /bin/bash ] && exec /bin/bash || exec /bin/sh)";
      const cmdArr = ["/bin/sh", "-c", inner];

      // 1) cria o exec com TTY ligado
      const headers = await authHeaders();
      const body = { AttachStdin: true, AttachStdout: true, AttachStderr: true, Tty: true, Cmd: cmdArr };
      if (flags.user && flags.user !== true) body.User = String(flags.user);
      const createRes = await fetch(`${URL}/api/endpoints/${epId}/docker/containers/${c.Id}/exec`, {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!createRes.ok) throw new Error(`exec create HTTP ${createRes.status}: ${await createRes.text()}`);
      const execId = (await createRes.json()).Id;

      // 2) token para o WebSocket: o Portainer aceita o JWT ou a API key no querystring `token`.
      const wsToken = await wsAuthToken();
      const wsBase = URL.replace(/^http/, "ws");
      const wsUrl = `${wsBase}/api/websocket/exec?id=${execId}&endpointId=${epId}&token=${encodeURIComponent(wsToken)}`;

      await runInteractiveWs(wsUrl, name);
      break;
    }

    default:
      console.log(`Portainer skill — comandos:
  status | endpoints
  ps [--all]
  start|stop|restart|kill|rm <id|nome> [--force]
  logs <id|nome> [--tail N] | inspect <id|nome>
  exec <id|nome> -- <comando...>   |   exec <id|nome> "<cmd shell>"   [--user <u>] [--workdir <dir>]
  shell <id|nome> [--cmd "<shell>"] [--user <u>]     # console interativo (WebSocket)
  stacks | stack-get <id|nome>
  stack-start|stack-stop|stack-rm <id|nome>
  stack-update <id|nome> --file ./compose.yml
  images | image-pull <img:tag> | volumes | networks

Globais: --endpoint <id>  --json`);
  }
}

// Token usado no querystring do WebSocket. O endpoint /api/websocket/exec exige um
// JWT — a API key (X-API-Key), que serve para o REST, NÃO é aceita aqui. Logo o
// shell interativo precisa de PORTAINER_USER/PORTAINER_PASS para emitir o JWT.
async function wsAuthToken() {
  if (jwt) return jwt;
  if (USER && PASS) {
    const r = await fetch(`${URL}/api/auth`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: USER, password: PASS }),
    });
    if (!r.ok) throw new Error(`falha no login para o WebSocket (HTTP ${r.status})`);
    jwt = (await r.json()).jwt;
    return jwt;
  }
  throw new Error(
    "O shell interativo precisa de JWT: defina PORTAINER_USER e PORTAINER_PASS em " +
    "~/.claude/secrets/portainer.env. A API key (PORTAINER_TOKEN) serve para os demais " +
    "comandos, mas não é aceita pelo WebSocket do Portainer."
  );
}

// Abre o WebSocket e faz a ponte com o terminal local (raw mode).
function runInteractiveWs(wsUrl, name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    ws.binaryType = "arraybuffer";
    const stdin = process.stdin;
    const wasRaw = stdin.isTTY ? stdin.isRaw : false;

    const cleanup = () => {
      try { if (stdin.isTTY) stdin.setRawMode(wasRaw); } catch {}
      stdin.pause();
      stdin.removeAllListeners("data");
    };

    ws.onopen = () => {
      console.error(`[conectado a ${name} — saia com "exit" ou Ctrl+D]`);
      if (stdin.isTTY) stdin.setRawMode(true);
      stdin.resume();
      stdin.on("data", (chunk) => {
        // Ctrl+D (0x04) encerra a sessão local
        if (chunk.length === 1 && chunk[0] === 4) { ws.close(); return; }
        if (ws.readyState === WebSocket.OPEN) ws.send(chunk);
      });
    };
    ws.onmessage = (ev) => {
      const data = typeof ev.data === "string" ? Buffer.from(ev.data) : Buffer.from(ev.data);
      process.stdout.write(data);
    };
    ws.onclose = () => { cleanup(); console.error(`\n[sessão encerrada: ${name}]`); resolve(); };
    ws.onerror = (e) => { cleanup(); reject(new Error(`WebSocket: ${e.message || "falha de conexão"}`)); };
  });
}

// desmultiplexa o stream de logs do Docker (frames de 8 bytes de cabeçalho)
function demuxDockerLogs(buf) {
  // se não parece multiplexado (TTY), devolve direto
  let out = "";
  let i = 0;
  while (i + 8 <= buf.length) {
    const type = buf[i];
    // type 0/1/2 = stdin/stdout/stderr no formato multiplexado
    if (type > 2) {
      // provavelmente não multiplexado — devolve o resto cru
      return buf.toString("utf8");
    }
    const len = buf.readUInt32BE(i + 4);
    out += buf.toString("utf8", i + 8, i + 8 + len);
    i += 8 + len;
  }
  return out || buf.toString("utf8");
}

main().catch((e) => {
  console.error("Erro:", e.message);
  process.exit(1);
});
