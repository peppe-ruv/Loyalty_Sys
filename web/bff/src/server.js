import http from "node:http";

const PORT = process.env.PORT || 3001;
const READ_MODEL = process.env.READ_MODEL_URL || "http://read-model:8088";
const TIER = process.env.TIER_URL || "http://tier-service:8084";
const CONTEST = process.env.CONTEST_URL || "http://contest-service:8086";
const CMS = process.env.CMS_URL || "http://cms:3000";

// cache leggera per il degrado controllato (RF-54): ultimo saldo noto per membro
const lastKnown = new Map();

async function fetchJson(url, init, timeoutMs = 1500) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { ...init, signal: ctrl.signal });
    if (!r.ok) throw new Error(`${url} -> ${r.status}`);
    return await r.json();
  } finally { clearTimeout(t); }
}

async function summary(memberId) {
  try {
    const [tier] = await Promise.all([fetchJson(`${TIER}/v1/tiers/members/${memberId}`)]);
    const s = { memberId, tier: tier.tier, statusPointsYear: tier.statusPointsYear, pointsToNext: tier.pointsToNext, updatedAt: new Date().toISOString(), stale: false };
    lastKnown.set(memberId, s);
    return s;
  } catch (e) {
    const cached = lastKnown.get(memberId);
    if (cached) return { ...cached, stale: true };
    throw e;
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const json = (code, body) => { res.writeHead(code, { "content-type": "application/json" }); res.end(JSON.stringify(body)); };
  try {
    if (url.pathname === "/healthz") return json(200, { status: "UP" });
    let m;
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/summary$/))) return json(200, await summary(m[1]));
    if ((m = url.pathname.match(/^\/api\/contests\/([^/]+)\/plays$/)) && req.method === "POST") {
      let body = ""; for await (const c of req) body += c;
      const out = await fetchJson(`${CONTEST}/v1/contests/${m[1]}/plays`, { method: "POST", headers: { "content-type": "application/json", "x-forwarded-for": req.socket.remoteAddress }, body }, 3000);
      return json(200, out);
    }
    if (url.pathname === "/api/content/cards") return json(200, await fetchJson(`${CMS}/api/contest-cards?where[status][equals]=published`));
    json(404, { error: "NOT_FOUND" });
  } catch (e) {
    json(503, { error: "UPSTREAM_UNAVAILABLE", detail: String(e.message) });
  }
});
server.listen(PORT, () => console.log(`bff listening on ${PORT}`));
