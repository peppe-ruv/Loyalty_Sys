import http from "node:http";
import client from "prom-client";

import { idempotencyKey } from "./keys.js";
import { authHeaders } from "./tokens.js";

// RF-117: metriche del BFF (richieste per rotta e latenza) su /metrics, raccolte dal ServiceMonitor
const registry = new client.Registry();
client.collectDefaultMetrics({ register: registry, prefix: "bff_" });
const httpRequests = new client.Histogram({ name: "bff_http_request_duration_seconds", help: "Durata richieste BFF", labelNames: ["route", "method", "status"], buckets: [0.01, 0.05, 0.1, 0.2, 0.5, 1, 2, 5], registers: [registry] });
const routeOf = (p) => p.replace(/\/[0-9a-f-]{8,}(?=\/|$)/gi, "/{id}").replace(/\/members\/[^/]+/, "/members/{id}");

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
const DECISION = process.env.DECISION_URL || "http://decision-service:8093";
const FRAUD = process.env.FRAUD_URL || "http://fraud-service:8094";
const NOTIFIER = process.env.NOTIFIER_URL || "http://notifier:8089";
const IDENTITY = process.env.IDENTITY_URL || "http://identity-mapping:8087";

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
    // Token di servizio quando le API interne lo pretendono (RF-43); in locale non c'è e non si aggiunge nulla.
    const auth = await authHeaders();
    const r = await fetch(url, { ...init, headers: { ...(init?.headers || {}), ...auth }, signal: ctrl.signal });
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
  const started = process.hrtime.bigint();
  const observe = (code) => httpRequests.labels(routeOf(url.pathname), req.method, String(code)).observe(Number(process.hrtime.bigint() - started) / 1e9);
  const json = (code, body) => { observe(code); res.writeHead(code, { "content-type": "application/json" }); res.end(JSON.stringify(body)); };
  try {
    if (url.pathname === "/healthz") return json(200, { status: "UP" });
    if (url.pathname === "/metrics") { res.writeHead(200, { "content-type": registry.contentType }); return res.end(await registry.metrics()); }
    let m;
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/summary$/))) return json(200, await summary(m[1]));
    if ((m = url.pathname.match(/^\/api\/contests\/([^/]+)\/plays$/)) && req.method === "POST") {
      let body = ""; for await (const c of req) body += c;
      const out = await fetchJson(`${CONTEST}/v1/contests/${m[1]}/plays`, { method: "POST", headers: { "content-type": "application/json", "x-forwarded-for": req.socket.remoteAddress }, body }, 3000);
      return json(200, out);
    }
    if (url.pathname === "/api/content/cards") return json(200, await fetchJson(`${CMS}/api/contest-cards?where[status][equals]=published`));

    // --- Area membro (RF-72, RF-68, RF-69, RF-67, RF-16/17, RF-73) — "client cockpit" ---
    // Nelle rotte che inoltrano il corpo, i campi presi dal percorso (memberId, canale) si scrivono
    // DOPO lo spread: un corpo con "memberId" non deve poter parlare a nome di un altro membro.
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/enroll$/)) && req.method === "POST")
      return json(201, await proxy(`${MEMBERS}/v1/members`, "POST", JSON.stringify({ ...JSON.parse((await readBody(req)) || "{}"), memberId: m[1] })));
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
      return json(202, await proxy(`${INGRESS}/v1/check-ins`, "POST", JSON.stringify({ ...JSON.parse((await readBody(req)) || "{}"), memberId: m[1], channel: "app" })));
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
      // Il riferimento lo porta il client: con una chiave presa dall'orologio, un ritentativo
      // trasferirebbe le unità una seconda volta.
      if (!b.clientRef) return json(400, { error: "CLIENT_REF_REQUIRED" });
      const transferKey = idempotencyKey("p2p", [m[1], b.toMemberId, b.clientRef], "TRANSFER");
      return json(201, await proxy(`${LEDGER}/v1/ledger/transfers`, "POST", JSON.stringify({ fromMemberId: m[1], toMemberId: b.toMemberId, currency: b.wallet || "PREMIO", amount: b.amount, transferKey, comment: b.comment || "" })));
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
      return json(200, await proxy(`${CATALOG}/v1/pay-with-points`, "POST", JSON.stringify({ ...JSON.parse((await readBody(req)) || "{}"), memberId: m[1] })));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/custom-fields$/))) return json(200, await fetchJson(`${MEMBERS}/v1/members/${m[1]}/custom-fields`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/custom-fields\/([^/]+)$/)) && req.method === "PUT") return json(200, await proxy(`${MEMBERS}/v1/members/${m[1]}/custom-fields/${m[2]}`, "PUT", await readBody(req)));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/tier-progress$/))) return json(200, await fetchJson(`${TIER}/v1/tiers/members/${m[1]}/progress`));
    if (url.pathname === "/api/content/campaigns") return json(200, await fetchJson(`${CMS}/api/campaigns?where[status][equals]=published&where[visibility.mode][not_equals]=HIDDEN`));
    if (url.pathname === "/api/content/challenges") return json(200, await fetchJson(`${CMS}/api/challenges?where[status][equals]=published`));

    // --- Loyalty 4.0 (RF-125..RF-136): Next Best Action, inbox/offerte, consensi, identità del canale ---
    // NBA per il sito/app: contesto del canale (pagina, carrello, dispositivo) → una sola azione con motivo; degrado a NO_ACTION se il motore non risponde
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/next-best-action$/)) && req.method === "POST") {
      const ctx = JSON.parse((await readBody(req)) || "{}");
      try { return json(200, await proxy(`${DECISION}/v1/decisions/next-best-action/${m[1]}`, "POST", JSON.stringify({ context: { channel: "web", ...ctx }, persist: true }))); }
      catch (e) { return json(200, { customerId: m[1], action: "NO_ACTION", reason: "decision engine unavailable", metadata: { degraded: true } }); }
    }
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/inbox$/))) return json(200, await fetchJson(`${NOTIFIER}/v1/inbox/${m[1]}`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/inbox\/([^/]+)\/(read|accept|dismiss)$/)) && req.method === "POST") return json(200, await proxy(`${NOTIFIER}/v1/inbox/${m[1]}/${m[2]}/${m[3]}`, "POST", "{}"));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/consents$/)) && req.method === "GET") return json(200, await fetchJson(`${MEMBERS}/v1/members/${m[1]}/consents`));
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/consents$/)) && req.method === "POST") {
      const c = JSON.parse((await readBody(req)) || "{}");
      return json(200, await proxy(`${MEMBERS}/v1/members/${m[1]}/consents`, "POST", JSON.stringify({ source: "web", evidence: `session:${req.headers["x-session-id"] || "-"}`, ...c })));
    }
    if (url.pathname === "/api/consent-purposes") return json(200, await fetchJson(`${MEMBERS}/v1/members/consent-purposes`));
    // Identità: al login il canale dichiara i propri identificatori (sub OIDC, dispositivo, app) e riceve l'id canonico
    if (url.pathname === "/api/identity/resolve" && req.method === "POST") {
      const b = JSON.parse((await readBody(req)) || "{}");
      return json(200, await proxy(`${IDENTITY}/v1/identities/resolve`, "POST", JSON.stringify({ source: "web", channel: "web", ...b })));
    }
    // Eventi comportamentali dal sito (RF-133): pagina prodotto, carrello → azioni canoniche (nessun dato personale)
    if ((m = url.pathname.match(/^\/api\/members\/([^/]+)\/events$/)) && req.method === "POST") {
      const ev = JSON.parse((await readBody(req)) || "{}");
      const allowed = ["PRODUCT_VIEWED", "PRODUCT_ADDED_TO_CART", "OFFER_ACCEPTED"];
      if (!allowed.includes(ev.actionType)) return json(400, { error: "ACTION_NOT_ALLOWED", allowed });
      // `clientRef` rende il ritentativo innocuo; senza, resta la finestra del minuto (un evento
      // di navigazione ripetuto non fa danno, ma è il client a dover portare il riferimento).
      const occurredAt = ev.occurredAt || new Date().toISOString();
      const key = idempotencyKey("web", [m[1], ev.ref, ev.clientRef || Math.floor(Date.parse(occurredAt) / 60000)], ev.actionType);
      return json(202, await proxy(`${INGRESS}/v1/actions`, "POST", JSON.stringify({ items: [{ memberId: m[1], action: { actionType: ev.actionType, idempotencyKey: key, externalRef: ev.ref, occurredAt, attributes: { channel: "web", deviceId: req.headers["x-device-id"] || "", ...(ev.attributes || {}) } } }] })));
    }

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
        // Un'azione da sportello accredita punti: senza riferimento del client un doppio clic
        // la applicherebbe due volte.
        if (!a.idempotencyKey && !a.clientRef) return json(400, { error: "CLIENT_REF_REQUIRED" });
        const key = a.idempotencyKey || idempotencyKey("operator", [actor, m[1], a.clientRef], a.actionType);
        return json(202, await proxy(`${INGRESS}/v1/actions`, "POST", JSON.stringify({ items: [{ memberId: m[1], action: { ...a, idempotencyKey: key, occurredAt: new Date().toISOString(), attributes: { channel: "sportello", operator: actor, ...(a.attributes || {}) } } }] })));
      }
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/codes$/)) && req.method === "POST") {
        const { code } = JSON.parse((await readBody(req)) || "{}");
        const r = await fetch(`${INGRESS}/v1/codes/redeem`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ memberId: m[1], code, channel: "sportello" }) });
        return json(r.status, await r.json());
      }
      if ((m = url.pathname.match(/^\/api\/operator\/redemptions\/([^/]+)\/(use|deliver)$/)) && req.method === "POST")
        return json(200, await proxy(`${CATALOG}/v1/redemptions/${m[1]}/transitions`, "POST", JSON.stringify({ to: m[2] === "use" ? "USED" : "DELIVERED", actor, byMember: false })));
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/badges\/([^/]+)$/)) && req.method === "POST") {
        const b = JSON.parse((await readBody(req)) || "{}");
        if (!b.clientRef) return json(400, { error: "CLIENT_REF_REQUIRED" });
        return json(201, await proxy(`${ENGAGEMENT}/v1/badges/${m[2]}/grants`, "POST", JSON.stringify({ memberId: m[1], grantKey: idempotencyKey("operator", [actor, m[1], m[2], b.clientRef], "GRANT_BADGE") })));
      }
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/blocks$/)) && req.method === "POST") {
        const b = JSON.parse((await readBody(req)) || "{}");
        if (!b.clientRef) return json(400, { error: "CLIENT_REF_REQUIRED" });
        return json(202, await proxy(`${LEDGER}/v1/ledger/blocks?unblock=${url.searchParams.get("unblock") || "false"}`, "POST", JSON.stringify({ memberId: m[1], actionKey: idempotencyKey("block", [actor, m[1], b.clientRef], "BLOCK"), ...b })));
      }
      if (url.pathname === "/api/operator/simulations" && req.method === "POST") return json(200, await proxy(`${RULES}/v1/simulations`, "POST", await readBody(req)));
      // Loyalty 4.0: decisioni spiegabili, rischio, coda contatti, consensi e identità del membro nella console
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/decisions$/))) return json(200, await fetchJson(`${DECISION}/v1/decisions/members/${m[1]}`));
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/risk$/))) return json(200, await fetchJson(`${FRAUD}/v1/risk/members/${m[1]}`));
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/risk\/assess$/)) && req.method === "POST") return json(200, await proxy(`${FRAUD}/v1/risk/members/${m[1]}/assess`, "POST", "{}"));
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/identities$/))) return json(200, await fetchJson(`${IDENTITY}/v1/identities/members/${m[1]}`));
      if (url.pathname === "/api/operator/identities/merges" && req.method === "POST") return json(200, await proxy(`${IDENTITY}/v1/identities/merges`, "POST", JSON.stringify({ ...JSON.parse((await readBody(req)) || "{}"), actor })));
      if ((m = url.pathname.match(/^\/api\/operator\/identities\/merges\/([^/]+)\/unmerge$/)) && req.method === "POST") return json(200, await proxy(`${IDENTITY}/v1/identities/merges/${m[1]}/unmerge`, "POST", await readBody(req)));
      if (url.pathname === "/api/operator/queue") return json(200, await fetchJson(`${NOTIFIER}/v1/operator-queue`));
      if ((m = url.pathname.match(/^\/api\/operator\/queue\/([^/]+)\/handle$/)) && req.method === "POST") return json(200, await proxy(`${NOTIFIER}/v1/operator-queue/${m[1]}/handle`, "POST", JSON.stringify({ operator: actor, ...JSON.parse((await readBody(req)) || "{}") })));
      if ((m = url.pathname.match(/^\/api\/operator\/members\/([^/]+)\/consents$/)) && req.method === "POST")
        return json(200, await proxy(`${MEMBERS}/v1/members/${m[1]}/consents`, "POST", JSON.stringify({ source: "call-center", evidence: `operator:${actor}`, ...JSON.parse((await readBody(req)) || "{}") })));
      if (url.pathname === "/api/operator/risk/top") return json(200, await fetchJson(`${FRAUD}/v1/risk/top`));
      // Consegne fallite e reinvio manuale (RF-132): senza questa coda un messaggio non consegnato resta perso.
      if (url.pathname === "/api/operator/deliveries/failed") return json(200, await fetchJson(`${NOTIFIER}/v1/deliveries/failed`));
      if ((m = url.pathname.match(/^\/api\/operator\/deliveries\/([^/]+)\/resend$/)) && req.method === "POST")
        return json(200, await proxy(`${NOTIFIER}/v1/deliveries/${m[1]}/resend`, "POST", JSON.stringify({ ...JSON.parse((await readBody(req)) || "{}"), operator: actor })));
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
