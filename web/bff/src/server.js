import http from "node:http";

const PORT = process.env.PORT || 3001;
const READ_MODEL = process.env.READ_MODEL_URL || "http://read-model:8088";
const TIER = process.env.TIER_URL || "http://tier-service:8084";
const CONTEST = process.env.CONTEST_URL || "http://contest-service:8086";
const CMS = process.env.CMS_URL || "http://cms:3000";
const MEMBERS = process.env.MEMBER_URL || "http://member-service:8091";
const SEGMENTS = process.env.SEGMENT_URL || "http://segment-service:8090";
const CATALOG = process.env.CATALOG_URL || "http://catalog-redemption:8085";
const INGRESS = process.env.INGRESS_URL || "http://ingress-adapters:8081";
const LEDGER = process.env.LEDGER_URL || "http://ledger:8083";
const ENGAGEMENT = process.env.ENGAGEMENT_URL || "http://engagement-service:8092";
const RULES = process.env.RULES_URL || "http://rules-engine:8082";

// Ruoli (RF-43): il gateway OIDC mette in x-roles i ruoli dell'utente; l'area operatore richiede customer_care o sportello.
const OPERATOR_ROLES = ["customer_care", "sportello", "platform_admin"];
const isOperator = (req) => (req.headers["x-roles"] || "").split(",").some((r) => OPERATOR_ROLES.includes(r.trim()));
const readBody = async (req) => { let b = ""; for await (const c of req) b += c; return b; };
const proxy = (url, method, body, headers = {}) => fetchJson(url, { method, headers: { "content-type": "application/json", ...headers }, body }, 3000);

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

    // --- Area membro (RF-72, RF-68, RF-69, RF-67, RF-16/17, RF-73) — "client cockpit" ---
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/enroll$/)) && req.method === "POST")
      return json(201, await proxy(`${MEMBERS}/v1/members`, "POST", JSON.stringify({ memberId: m[1], ...JSON.parse((await readBody(req)) || "{}") })));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/profile$/)) && req.method === "PATCH")
      return json(200, await proxy(`${MEMBERS}/v1/members/${m[1]}`, "PATCH", await readBody(req)));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/referral$/))) return json(200, await fetchJson(`${MEMBERS}/v1/members/${m[1]}/referral`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/movements$/))) return json(200, await fetchJson(`${LEDGER}/v1/ledger/members/${m[1]}/movements`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/rewards$/))) return json(200, await fetchJson(`${READ_MODEL}/v1/read/members/${m[1]}/redemptions`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/segments$/))) return json(200, await fetchJson(`${SEGMENTS}/v1/segments/members/${m[1]}`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/codes$/)) && req.method === "POST") {
      const { code } = JSON.parse((await readBody(req)) || "{}");
      const r = await fetch(`${INGRESS}/v1/codes/redeem`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ memberId: m[1], code, channel: "web" }) });
      return json(r.status, await r.json());
    }
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/check-ins$/)) && req.method === "POST")
      return json(202, await proxy(`${INGRESS}/v1/check-ins`, "POST", JSON.stringify({ memberId: m[1], ...JSON.parse((await readBody(req)) || "{}"), channel: "app" })));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/redemptions$/)) && req.method === "POST") {
      const { rewardId } = JSON.parse((await readBody(req)) || "{}");
      const [tier, segs, bal] = await Promise.all([fetchJson(`${TIER}/v1/tiers/members/${m[1]}`), fetchJson(`${SEGMENTS}/v1/segments/members/${m[1]}`).catch(() => []), fetchJson(`${LEDGER}/v1/ledger/members/${m[1]}/balances`)]);
      const premio = (bal.find((b) => b.currency === "PREMIO") || { available: 0 }).available;
      const order = { BASE: 0, PLUS: 1, TOP: 2 }[tier.tier] ?? 0;
      return json(201, await proxy(`${CATALOG}/v1/redemptions`, "POST", JSON.stringify({ memberId: m[1], memberTierOrder: order, memberSegments: segs, premioAvailable: premio, rewardId })));
    }
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/redemptions\/([^/]+)\/cancel$/)) && req.method === "POST")
      return json(200, await proxy(`${CATALOG}/v1/redemptions/${m[2]}/transitions`, "POST", JSON.stringify({ to: "CANCELLED", actor: m[1], byMember: true })));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/export$/))) return json(200, await fetchJson(`${MEMBERS}/v1/members/${m[1]}/export`, {}, 5000));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/anonymize$/)) && req.method === "POST") return json(200, await proxy(`${MEMBERS}/v1/members/${m[1]}/anonymize`, "POST"));

    // --- Gamification e wallet (RF-87..RF-97, RF-104) ---
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/wallets$/))) return json(200, await fetchJson(`${LEDGER}/v1/ledger/members/${m[1]}/wallets`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/transfers$/)) && req.method === "POST") {
      const b = JSON.parse((await readBody(req)) || "{}");
      return json(201, await proxy(`${LEDGER}/v1/ledger/transfers`, "POST", JSON.stringify({ fromMemberId: m[1], toMemberId: b.toMemberId, currency: b.wallet || "PREMIO", amount: b.amount, transferKey: `p2p:${m[1]}:${b.toMemberId}:${b.clientRef || Date.now()}`, comment: b.comment || "" })));
    }
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/badges$/))) return json(200, await fetchJson(`${ENGAGEMENT}/v1/badges/members/${m[1]}/details`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/achievements$/))) return json(200, await fetchJson(`${ENGAGEMENT}/v1/achievements/members/${m[1]}`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/challenges$/))) return json(200, await fetchJson(`${ENGAGEMENT}/v1/challenges/members/${m[1]}`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/leaderboards\/([^/]+)$/))) return json(200, await fetchJson(`${ENGAGEMENT}/v1/leaderboards/${m[2]}/members/${m[1]}`));
    if ((m = url.pathname.match(/^\/api\/leaderboards\/([^/]+)$/))) return json(200, await fetchJson(`${ENGAGEMENT}/v1/leaderboards/${m[1]}?${url.searchParams}`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/wheels\/([^/]+)\/spins$/)) && req.method === "POST") {
      const b = JSON.parse((await readBody(req)) || "{}");
      const r = await fetch(`${CONTEST}/v1/wheels/${m[2]}/spins`, { method: "POST", headers: { "content-type": "application/json", "x-forwarded-for": req.socket.remoteAddress }, body: JSON.stringify({ memberId: m[1], deviceFingerprint: b.deviceFingerprint }) });
      return json(r.status, await r.json());
    }
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/pay-with-points$/)) && req.method === "POST")
      return json(200, await proxy(`${CATALOG}/v1/pay-with-points`, "POST", JSON.stringify({ memberId: m[1], ...JSON.parse((await readBody(req)) || "{}") })));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/custom-fields$/))) return json(200, await fetchJson(`${MEMBERS}/v1/members/${m[1]}/custom-fields`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/custom-fields\/([^/]+)$/)) && req.method === "PUT") return json(200, await proxy(`${MEMBERS}/v1/members/${m[1]}/custom-fields/${m[2]}`, "PUT", await readBody(req)));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/tier-progress$/))) return json(200, await fetchJson(`${TIER}/v1/tiers/members/${m[1]}/progress`));
    if (url.pathname === "/api/content/campaigns") return json(200, await fetchJson(`${CMS}/api/campaigns?where[status][equals]=published&where[visibility.mode][not_equals]=HIDDEN`));
    if (url.pathname === "/api/content/challenges") return json(200, await fetchJson(`${CMS}/api/challenges?where[status][equals]=published`));

    // --- Postazione operatore (RF-75): sportello/negozio/call center — "merchant panel" ---
    if (url.pathname.startsWith("/api/operator/")) {
      if (!isOperator(req)) return json(403, { error: "FORBIDDEN" });
      const actor = req.headers["x-user"] || "operator";
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)$/))) {
        const [member, s, bal] = await Promise.all([fetchJson(`${MEMBERS}/v1/members/${m[1]}`), summary(m[1]), fetchJson(`${LEDGER}/v1/ledger/members/${m[1]}/balances`)]);
        return json(200, { member, summary: s, balances: bal });
      }
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/actions$/)) && req.method === "POST") {
        const a = JSON.parse((await readBody(req)) || "{}");
        const key = a.idempotencyKey || `operator:${actor}:${m[1]}:${a.actionType}:${Date.now()}`;
        return json(202, await proxy(`${INGRESS}/v1/actions`, "POST", JSON.stringify({ items: [{ memberId: m[1], action: { ...a, idempotencyKey: key, occurredAt: new Date().toISOString(), attributes: { channel: "sportello", operator: actor, ...(a.attributes || {}) } } }] })));
      }
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/codes$/)) && req.method === "POST") {
        const { code } = JSON.parse((await readBody(req)) || "{}");
        const r = await fetch(`${INGRESS}/v1/codes/redeem`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ memberId: m[1], code, channel: "sportello" }) });
        return json(r.status, await r.json());
      }
      if ((m = url.pathname.match(/^\/api\/operator\/redemptions\/([^/]+)\/(use|deliver)$/)) && req.method === "POST")
        return json(200, await proxy(`${CATALOG}/v1/redemptions/${m[1]}/transitions`, "POST", JSON.stringify({ to: m[2] === "use" ? "USED" : "DELIVERED", actor, byMember: false })));
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/badges\/([^/]+)$/)) && req.method === "POST")
        return json(201, await proxy(`${ENGAGEMENT}/v1/badges/${m[2]}/grants`, "POST", JSON.stringify({ memberId: m[1], grantKey: `operator:${actor}:${m[1]}:${m[2]}:${Date.now()}` })));
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/blocks$/)) && req.method === "POST")
        return json(202, await proxy(`${LEDGER}/v1/ledger/blocks?unblock=${url.searchParams.get("unblock") || "false"}`, "POST", JSON.stringify({ memberId: m[1], actionKey: `block:${actor}:${m[1]}:${Date.now()}`, ...JSON.parse((await readBody(req)) || "{}") })));
      if (url.pathname === "/api/operator/simulations" && req.method === "POST") return json(200, await proxy(`${RULES}/v1/simulations`, "POST", await readBody(req)));
      if ((m = url.pathname.match(/^\/api\/operator\/redemptions\/transitions$/)) && req.method === "POST")
        return json(200, await proxy(`${CATALOG}/v1/redemptions/transitions`, "POST", JSON.stringify({ ...JSON.parse((await readBody(req)) || "{}"), actor })));
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/tier$/)) && req.method === "PUT")
        return json(200, await proxy(`${TIER}/v1/tiers/members/${m[1]}/override`, "PUT", JSON.stringify({ ...JSON.parse((await readBody(req)) || "{}"), actor })));
    }
    json(404, { error: "NOT_FOUND" });
  } catch (e) {
    json(503, { error: "UPSTREAM_UNAVAILABLE", detail: String(e.message) });
  }
});
server.listen(PORT, () => console.log(`bff listening on ${PORT}`));
