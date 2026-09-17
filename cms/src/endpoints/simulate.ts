/**
 * RF-127/RF-131: "prova prima di pubblicare". Il backoffice invia al decision-service e al fraud-service la policy in
 * bozza (anche non ancora pubblicata) con un membro reale o descritto a mano e mostra la decisione o la valutazione
 * di rischio con i motivi. Nessun effetto collaterale: le simulazioni non sono registrate né eseguite.
 */
import type { Endpoint, PayloadRequest } from "payload";

const DECISION_URL = process.env.DECISION_URL || "http://decision-service:8093";
const FRAUD_URL = process.env.FRAUD_URL || "http://fraud-service:8094";

async function proxy(url: string, body: unknown) {
  const res = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  return Response.json(await res.json().catch(() => ({ error: `HTTP ${res.status}` })), { status: res.status });
}

export const simulateDecision: Endpoint = {
  path: "/simulate/decision", method: "post",
  handler: async (req: PayloadRequest) => {
    if (!req.user) return Response.json({ error: "UNAUTHORIZED" }, { status: 401 });
    const body = await (req as any).json();
    return proxy(`${DECISION_URL}/v1/decisions/simulate`, body);
  },
};

export const simulateRisk: Endpoint = {
  path: "/simulate/risk", method: "post",
  handler: async (req: PayloadRequest) => {
    if (!req.user) return Response.json({ error: "UNAUTHORIZED" }, { status: 401 });
    const body = await (req as any).json();
    return proxy(`${FRAUD_URL}/v1/risk/simulate`, body);
  },
};

/** Vista "Decisioni" del backoffice: decision log di un membro e statistiche delle ultime 24 ore. */
export const decisionLog: Endpoint = {
  path: "/decisions/:memberId", method: "get",
  handler: async (req: PayloadRequest) => {
    if (!req.user) return Response.json({ error: "UNAUTHORIZED" }, { status: 401 });
    const memberId = (req as any).routeParams?.memberId;
    const res = await fetch(`${DECISION_URL}/v1/decisions/members/${encodeURIComponent(memberId)}`);
    return Response.json(await res.json().catch(() => []), { status: res.status });
  },
};
