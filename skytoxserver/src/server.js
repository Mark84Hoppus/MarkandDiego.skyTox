const path = require("node:path");
const express = require("express");
const admin = require("firebase-admin");
const dotenv = require("dotenv");

dotenv.config({ path: path.join(__dirname, "..", ".env") });

const host = process.env.HOST || "0.0.0.0";
const port = Number.parseInt(process.env.PORT || "8787", 10);
const apiKey = process.env.SKYTOX_PUSH_API_KEY || "";
const packageName = process.env.SKYTOX_ANDROID_PACKAGE || "markanddiego.skytox";
const credentialsPath = process.env.GOOGLE_APPLICATION_CREDENTIALS || "serviceAccount.json";
const dryRun = String(process.env.SKYTOX_DRY_RUN || "false").toLowerCase() === "true";

if (!apiKey || apiKey === "change-this-to-a-long-random-secret") {
  console.error("SKYTOX_PUSH_API_KEY is not configured. Edit .env first.");
  process.exit(1);
}

if (!dryRun) {
  const serviceAccountPath = path.isAbsolute(credentialsPath)
    ? credentialsPath
    : path.join(__dirname, "..", credentialsPath);

  try {
    const serviceAccount = require(serviceAccountPath);
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
    });
  } catch (error) {
    console.error(`Cannot load Firebase service account: ${serviceAccountPath}`);
    console.error(error.message);
    process.exit(1);
  }
}

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "8kb" }));

const recentRequests = new Map();
const RATE_WINDOW_MS = 60 * 1000;
const RATE_LIMIT_PER_TOKEN = 10;

function log(message) {
  console.log(`${new Date().toISOString()} ${message}`);
}

function pruneRateLimit(now) {
  for (const [key, value] of recentRequests.entries()) {
    if (value.resetAt <= now) {
      recentRequests.delete(key);
    }
  }
}

function checkRateLimit(token) {
  const now = Date.now();
  pruneRateLimit(now);

  const key = token.slice(0, 32);
  const entry = recentRequests.get(key) || { count: 0, resetAt: now + RATE_WINDOW_MS };
  entry.count += 1;
  recentRequests.set(key, entry);

  return entry.count <= RATE_LIMIT_PER_TOKEN;
}

function requireApiKey(req, res, next) {
  const provided = req.header("X-SkyTox-Key") || "";
  if (provided !== apiKey) {
    return res.status(401).json({ ok: false, error: "unauthorized" });
  }
  return next();
}

app.get("/health", (req, res) => {
  res.json({
    ok: true,
    service: "skytox-push-server",
    time: new Date().toISOString(),
  });
});

app.post("/push", requireApiKey, async (req, res) => {
  const token = typeof req.body?.token === "string" ? req.body.token.trim() : "";
  const reason = typeof req.body?.reason === "string" ? req.body.reason.trim().slice(0, 32) : "wake";
  const remote = req.headers["x-forwarded-for"] || req.socket.remoteAddress || "unknown";

  if (token.length < 20 || token.length > 4096) {
    log(`push rejected invalid_token remote=${remote}`);
    return res.status(400).json({ ok: false, error: "invalid_token" });
  }

  if (!checkRateLimit(token)) {
    log(`push rejected rate_limited remote=${remote} tokenPrefix=${token.slice(0, 12)}`);
    return res.status(429).json({ ok: false, error: "rate_limited" });
  }

  const message = {
    token,
    notification: {
      title: "skyTox",
      body: "Новое сообщение",
    },
    data: {
      type: "skytox_wakeup",
      reason,
      ts: String(Date.now()),
    },
    android: {
      priority: "high",
      restrictedPackageName: packageName,
      notification: {
        title: "skyTox",
        body: "Новое сообщение",
      },
    },
  };

  if (dryRun) {
    log(`DRY RUN push accepted remote=${remote} reason=${reason} tokenPrefix=${token.slice(0, 12)}`);
    return res.json({ ok: true, dryRun: true, id: `dry-run-${Date.now()}` });
  }

  try {
    const id = await admin.messaging().send(message);
    log(`push sent remote=${remote} reason=${reason} tokenPrefix=${token.slice(0, 12)} id=${id}`);
    return res.json({ ok: true, id });
  } catch (error) {
    log(`push failed remote=${remote} reason=${reason} tokenPrefix=${token.slice(0, 12)} code=${error.code || "unknown"} message=${error.message}`);
    return res.status(502).json({
      ok: false,
      error: "fcm_send_failed",
      code: error.code || "unknown",
    });
  }
});

app.use((req, res) => {
  res.status(404).json({ ok: false, error: "not_found" });
});

app.listen(port, host, () => {
  log(`skyTox push server listening on http://${host}:${port}`);
  if (dryRun) {
    log("DRY RUN mode is enabled. No Firebase messages will be sent.");
  }
});
