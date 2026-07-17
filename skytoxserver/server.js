"use strict";

const fs = require("fs");
const http = require("http");
const path = require("path");
const admin = require("firebase-admin");

const rootDir = __dirname;
const env = readEnv(path.join(rootDir, ".env"));

const PORT = intEnv("PORT", 8787);
const HOST = env.HOST || "0.0.0.0";
const SERVICE_ACCOUNT_PATH = path.resolve(rootDir, env.SERVICE_ACCOUNT_PATH || "serviceAccount.json");
const MAX_BODY_BYTES = intEnv("MAX_BODY_BYTES", 4096);
const TOKEN_COOLDOWN_MS = intEnv("TOKEN_COOLDOWN_MS", 30000);
const IP_COOLDOWN_MS = intEnv("IP_COOLDOWN_MS", 2000);
const LOG_FILE = path.resolve(rootDir, env.LOG_FILE || "skytoxserver.log");

const tokenLastSeen = new Map();
const ipLastSeen = new Map();

const serviceAccount = JSON.parse(fs.readFileSync(SERVICE_ACCOUNT_PATH, "utf8"));
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
});

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/health") {
      return json(res, 200, { ok: true, service: "skytox-push-server", time: new Date().toISOString() });
    }

    if (req.method !== "POST" || req.url !== "/push") {
      return json(res, 404, { ok: false, error: "not_found" });
    }

    const remote = remoteAddress(req);
    const body = await readJsonBody(req);
    const token = typeof body.token === "string" ? body.token.trim() : "";
    const reason = sanitizeReason(body.reason);

    if (!isPlausibleFcmToken(token)) {
      log(`reject remote=${remote} reason=${reason} cause=bad_token`);
      return json(res, 400, { ok: false, error: "bad_token" });
    }

    const ipWait = checkCooldown(ipLastSeen, remote, IP_COOLDOWN_MS);
    if (ipWait > 0) {
      log(`rate_limit_ip remote=${remote} waitMs=${ipWait} tokenPrefix=${tokenPrefix(token)}`);
      return json(res, 429, { ok: false, error: "rate_limited", retryAfterMs: ipWait });
    }

    const tokenWait = checkCooldown(tokenLastSeen, token, TOKEN_COOLDOWN_MS);
    if (tokenWait > 0) {
      log(`rate_limit_token remote=${remote} waitMs=${tokenWait} tokenPrefix=${tokenPrefix(token)}`);
      return json(res, 429, { ok: false, error: "rate_limited", retryAfterMs: tokenWait });
    }

    const response = await admin.messaging().send({
      token,
      data: {
        type: "skytox_wakeup",
        kind: "skytox_wake",
        reason,
        ts: String(Date.now()),
      },
      android: {
        priority: "high",
      },
    });

    log(`push_sent remote=${remote} reason=${reason} tokenPrefix=${tokenPrefix(token)} id=${response}`);
    return json(res, 200, { ok: true });
  } catch (error) {
    log(`error ${error && error.stack ? error.stack.replace(/\s+/g, " ") : String(error)}`);
    return json(res, 500, { ok: false, error: "server_error" });
  }
});

server.listen(PORT, HOST, () => {
  log(`skyTox push server listening on http://${HOST}:${PORT}`);
});

function readEnv(filePath) {
  if (!fs.existsSync(filePath)) return {};
  return fs.readFileSync(filePath, "utf8")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#") && line.includes("="))
    .reduce((acc, line) => {
      const index = line.indexOf("=");
      acc[line.slice(0, index).trim()] = line.slice(index + 1).trim();
      return acc;
    }, {});
}

function intEnv(name, fallback) {
  const raw = env[name] || process.env[name];
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      data += chunk;
      if (Buffer.byteLength(data, "utf8") > MAX_BODY_BYTES) {
        reject(new Error("body_too_large"));
        req.destroy();
      }
    });
    req.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

function isPlausibleFcmToken(token) {
  return token.length >= 40 &&
    token.length <= 2048 &&
    /^[A-Za-z0-9:_\-]+$/.test(token);
}

function sanitizeReason(reason) {
  if (typeof reason !== "string") return "wake";
  const cleaned = reason.replace(/[^A-Za-z0-9_.-]/g, "_").slice(0, 48);
  return cleaned || "wake";
}

function checkCooldown(map, key, cooldownMs) {
  const now = Date.now();
  const previous = map.get(key) || 0;
  const wait = previous + cooldownMs - now;
  if (wait > 0) return wait;
  map.set(key, now);
  if (map.size > 10000) pruneMap(map, now - cooldownMs * 4);
  return 0;
}

function pruneMap(map, threshold) {
  for (const [key, value] of map.entries()) {
    if (value < threshold) map.delete(key);
  }
}

function tokenPrefix(token) {
  return token.slice(0, 12);
}

function remoteAddress(req) {
  const forwarded = req.headers["cf-connecting-ip"] || req.headers["x-forwarded-for"];
  if (typeof forwarded === "string" && forwarded.trim()) {
    return forwarded.split(",")[0].trim();
  }
  return req.socket.remoteAddress || "unknown";
}

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
  });
  res.end(payload);
}

function log(message) {
  const line = `${new Date().toISOString()} ${message}\n`;
  fs.appendFile(LOG_FILE, line, () => {});
  process.stdout.write(line);
}
