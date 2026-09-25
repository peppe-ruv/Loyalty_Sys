import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { rows } from "@/test/testbook";
import type { Role } from "@/lib/persona/personas";
import { LifecycleBar } from "./LifecycleBar";
import { DuplicateButton } from "./DuplicateButton";

// Testbook TB-WEB §LIFE: barra del ciclo di vita (docs/08 §3.3) sulla macchina a stati di docs/03 §3.6, con i ruoli di
// docs/08 §2 (`object.edit` per SUBMIT/PUBLISH/PAUSE/RESUME/END/ARCHIVE, `object.approve` per APPROVE/REJECT) e la
// policy di docs/06 §7. Oracolo: per ogni stato × ruolo × policy, l'insieme ESATTO dei pulsanti (le transizioni vietate
// non compaiono) e se sono utilizzabili o disabilitati con tooltip (docs/07 §4).

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn(async () => new Response(JSON.stringify({}), { status: 200, headers: { "content-type": "application/json" } }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const ROLES: Role[] = ["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"];
const EDIT: Role[] = ["ADMIN", "MARKETING"];
const APPROVE: Role[] = ["ADMIN", "LEGAL"];

// Pulsanti attesi per stato (docs/08 §3.3; *Duplica* di ENDED è il DuplicateButton dell'intestazione, vedi LIFE-061).
const BUTTONS: Record<string, string[]> = {
  "DRAFT/true": ["Invia in revisione", "Archivia"],
  "DRAFT/false": ["Pubblica", "Archivia"],
  IN_REVIEW: ["Approva", "Rifiuta…"],
  APPROVED: ["Pubblica"],
  LIVE: ["Metti in pausa", "Termina"],
  PAUSED: ["Riprendi", "Termina"],
  ENDED: ["Archivia"],
  ARCHIVED: [],
};

function renderBar(role: Role, status: string, approvalRequired?: boolean, system = false) {
  return renderWithProviders(
    <LifecycleBar
      service="campaign"
      transitionsPath="/v1/campaigns/c1/transitions"
      status={status}
      approvalRequired={approvalRequired}
      system={system}
      onChanged={vi.fn()}
    />,
    role,
  );
}

const labels = () => screen.queryAllByRole("button").map((b) => b.textContent?.trim() ?? "");
const usable = (b: HTMLElement) => b.closest("[title]") === null;

interface Case {
  id: string;
  desc: string;
  role: Role;
  status: string;
  policy?: boolean;
  expected: string[];
  enabled: boolean;
}

let n = 0;
const nextId = () => `TB-WEB-LIFE-${String(++n).padStart(3, "0")}`;
const cases: Case[] = [];
for (const policy of [true, false]) {
  for (const role of ROLES) {
    cases.push({
      id: nextId(),
      desc: `DRAFT, approvazione richiesta=${policy}, ${role}`,
      role,
      status: "DRAFT",
      policy,
      expected: BUTTONS[`DRAFT/${policy}`],
      enabled: EDIT.includes(role),
    });
  }
}
for (const status of ["IN_REVIEW", "APPROVED", "LIVE", "PAUSED", "ENDED", "ARCHIVED"]) {
  for (const role of ROLES) {
    cases.push({
      id: nextId(),
      desc: `${status}, ${role}`,
      role,
      status,
      expected: BUTTONS[status],
      enabled: status === "IN_REVIEW" ? APPROVE.includes(role) : EDIT.includes(role),
    });
  }
}
// n = 40: DRAFT (10) + 6 stati × 5 ruoli (30).

it.each(rows(cases.map((c) => ({ ...c, desc: `${c.desc} → [${c.expected.join(", ")}] ${c.expected.length ? (c.enabled ? "utilizzabili" : "disabilitati con tooltip") : ""}`.trim() }))))(
  "[%s] %s",
  (_id, _desc, c) => {
    renderBar(c.role, c.status, c.policy);
    expect(labels()).toEqual(c.expected);
    for (const b of screen.queryAllByRole("button")) {
      expect(usable(b)).toBe(c.enabled);
      if (!c.enabled) expect(b.closest("[title]")?.getAttribute("title")).toMatch(/Richiede/);
    }
  },
);

// Policy non nota (servizio della policy addormentato o in caricamento): docs/08 §3.3 dice "Invia in revisione (se la
// policy lo richiede) OPPURE Pubblica" senza il caso "policy ignota".
const unknownPolicy = ROLES.map((role, i) => ({
  id: `TB-WEB-LIFE-${String(41 + i).padStart(3, "0")}`,
  desc: `DRAFT, policy non nota, ${role} → entrambe le vie (Invia in revisione, Pubblica) + Archivia`,
  role,
}));
it.each(rows(unknownPolicy))("[%s] %s", (_id, _desc, { role }) => {
  // TESTBOOK: ambiguo, vedi TB-WEB-LIFE-041…045 — con policy ignota la barra offre entrambe le vie (il servizio risponde
  // 409 APPROVAL_REQUIRED a una pubblicazione non ammessa, LIFE-052).
  renderBar(role, "DRAFT", undefined);
  expect(labels()).toEqual(["Invia in revisione", "Pubblica", "Archivia"]);
});

it("[TB-WEB-LIFE-046] stato sconosciuto (SCHEDULED come stato del servizio) → nessun pulsante", () => {
  renderBar("ADMIN", "SCHEDULED");
  expect(labels()).toEqual([]);
});

it("[TB-WEB-LIFE-047] campagna di sistema in DRAFT (senza approvazione) → Pubblica, niente Archivia", () => {
  renderBar("ADMIN", "DRAFT", false, true);
  expect(labels()).toEqual(["Pubblica"]);
});

it("[TB-WEB-LIFE-048] campagna di sistema ENDED → nessun pulsante (non si archivia)", () => {
  renderBar("ADMIN", "ENDED", undefined, true);
  expect(labels()).toEqual([]);
});

it("[TB-WEB-LIFE-049] Rifiuta: commento vuoto → invio disabilitato", () => {
  renderBar("LEGAL", "IN_REVIEW");
  fireEvent.click(screen.getByRole("button", { name: "Rifiuta…" }));
  expect(screen.getByRole("button", { name: "Rifiuta" })).toBeDisabled();
});

it("[TB-WEB-LIFE-050] Rifiuta: commento di soli spazi → invio disabilitato", () => {
  renderBar("LEGAL", "IN_REVIEW");
  fireEvent.click(screen.getByRole("button", { name: "Rifiuta…" }));
  fireEvent.change(screen.getByLabelText("Motivo del rifiuto"), { target: { value: "   " } });
  expect(screen.getByRole("button", { name: "Rifiuta" })).toBeDisabled();
});

it("[TB-WEB-LIFE-051] Rifiuta con commento → POST …/transitions {action: REJECT, comment}", async () => {
  renderBar("LEGAL", "IN_REVIEW");
  fireEvent.click(screen.getByRole("button", { name: "Rifiuta…" }));
  fireEvent.change(screen.getByLabelText("Motivo del rifiuto"), { target: { value: "Premio non conforme" } });
  fireEvent.click(screen.getByRole("button", { name: "Rifiuta" }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalled());
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
  expect(url).toBe("/api/lh/campaign/v1/campaigns/c1/transitions");
  expect(init.method).toBe("POST");
  expect(JSON.parse(String(init.body))).toEqual({ action: "REJECT", comment: "Premio non conforme" });
});

it("[TB-WEB-LIFE-052] Pubblica rifiutata con 409 APPROVAL_REQUIRED → invito a «Invia in revisione»", async () => {
  fetchMock.mockResolvedValueOnce(
    new Response(JSON.stringify({ code: "APPROVAL_REQUIRED", detail: "approval required" }), { status: 409, headers: { "content-type": "application/json" } }),
  );
  renderBar("MARKETING", "DRAFT", undefined);
  fireEvent.click(screen.getByRole("button", { name: "Pubblica" }));
  // docs/08 §3.3: la transizione parte dalla conferma del dialogo (LIFE-054).
  fireEvent.click(screen.getByRole("button", { name: "Conferma" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Serve l'approvazione: usa «Invia in revisione».");
});

it("[TB-WEB-LIFE-053] IN_REVIEW per chi non può decidere (MARKETING) → «In attesa di LEGAL da …»", () => {
  renderBar("MARKETING", "IN_REVIEW");
  expect(screen.getByText(/In attesa di LEGAL/)).toBeInTheDocument();
});

it("[TB-WEB-LIFE-054] ogni transizione apre un dialogo con commento prima dell'invio (Metti in pausa)", async () => {
  renderBar("MARKETING", "LIVE");
  fireEvent.click(screen.getByRole("button", { name: "Metti in pausa" }));
  // docs/08 §3.3: "Ogni transizione apre un dialogo con commento → POST …/transitions {action, comment}".
  await new Promise((r) => setTimeout(r, 0));
  expect(fetchMock).not.toHaveBeenCalled();
  expect(screen.getByRole("dialog")).toBeInTheDocument();
});

it("[TB-WEB-LIFE-055] la pill mostra lo stato corrente", () => {
  renderBar("ADMIN", "PAUSED");
  expect(screen.getByText("PAUSED")).toBeInTheDocument();
});

it("[TB-WEB-LIFE-061] Duplica (object.edit) per MARKETING → utilizzabile", () => {
  renderWithProviders(<DuplicateButton service="campaign" path="/v1/campaigns/c1/duplicate" hrefFor={(c) => `/x/${c.id}`} />, "MARKETING");
  expect(usable(screen.getByRole("button", { name: /Duplica/ }))).toBe(true);
});

it("[TB-WEB-LIFE-062] Duplica (object.edit) per ANALYST → disabilitato con tooltip", () => {
  renderWithProviders(<DuplicateButton service="campaign" path="/v1/campaigns/c1/duplicate" hrefFor={(c) => `/x/${c.id}`} />, "ANALYST");
  expect(usable(screen.getByRole("button", { name: /Duplica/ }))).toBe(false);
});
