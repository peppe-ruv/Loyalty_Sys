import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { it as t } from "@/lib/i18n/it";
import type { EnterpriseStatus, StatusTile, TileState } from "@/lib/hub/enterpriseStatus";
import {
  BACKOFFICE_LOGIN_HREF,
  EnterpriseEntrances,
  EnterpriseHubHeader,
  EnterpriseStatusPanel,
  EnterpriseStatusSkeleton,
  EnterpriseStatusView,
  ShowcaseBanner,
} from "./EnterpriseHub";
import HubLayout from "@/app/(hub)/layout";

// HUB-02 — Demo Hub enterprise (docs/18 §5, ADR-049, F2-DIST-09, M8.14 V5): login al posto delle persone, tessere
// hub/web/idp/cms + Postgres/Kafka, banner della vetrina, ritorno alla demo, portale senza ingresso (Q-619).

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

let fetchSpy: ReturnType<typeof vi.fn>;

beforeEach(() => {
  refresh.mockReset();
  fetchSpy = vi.fn(async () => new Response("{}", { status: 404 }));
  vi.stubGlobal("fetch", fetchSpy);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

function statusWith(over: Partial<Record<StatusTile["key"], TileState>> = {}): EnterpriseStatus {
  const s = (key: StatusTile["key"], def: TileState) => over[key] ?? def;
  return {
    tiles: [
      { key: "hub", group: "role", state: s("hub", "UP"), latencyMs: 12 },
      { key: "web", group: "role", state: s("web", "UP"), latencyMs: null },
      { key: "idp", group: "role", state: s("idp", "UP"), latencyMs: 30 },
      { key: "cms", group: "role", state: "NOT_INSTALLED", latencyMs: null },
      { key: "db", group: "infra", state: s("db", "UP"), latencyMs: null },
      { key: "kafka", group: "infra", state: s("kafka", "UP"), latencyMs: null },
    ],
    checkedAt: "2026-10-01T10:00:00.000Z",
  };
}

describe("HUB-02 ingressi", () => {
  it("senza sessione: un solo ingresso, il login del BFF verso il backoffice; nessuna persona", () => {
    render(<EnterpriseEntrances user={null} />);
    const login = screen.getByRole("link", { name: t.hubEnterprise.login });
    expect(login).toHaveAttribute("href", BACKOFFICE_LOGIN_HREF);
    expect(BACKOFFICE_LOGIN_HREF).toBe("/api/auth/login?returnTo=%2Fbackoffice");
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByText(/Marta Villa|Scegli la persona|Scegli il membro/)).toBeNull();
    expect(screen.getByText(/nessuna password è pubblicata/)).toBeInTheDocument();
  });

  it("con una sessione aperta: «Apri il backoffice» con il nome dal token", () => {
    render(<EnterpriseEntrances user={{ username: "op.rossi", name: "Anna Rossi" }} />);
    expect(screen.getByRole("link", { name: /Apri il backoffice/ })).toHaveAttribute("href", "/backoffice");
    expect(screen.getByText("Sessione aperta come Anna Rossi.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: t.hubEnterprise.login })).toBeNull();
  });

  it("il portale membri non ha ingresso finché Q-619 non lo apre", () => {
    const { container } = render(<EnterpriseEntrances user={null} />);
    const closed = screen.getByTestId("portal-closed");
    expect(within(closed).getByText(t.hubEnterprise.portalClosed)).toBeInTheDocument();
    expect(within(closed).queryByRole("link")).toBeNull();
    expect(container.querySelector("a[href^='/portal']")).toBeNull();
  });

  it("non chiama /api/persona né /v1/demo/personas", () => {
    render(
      <>
        <EnterpriseHubHeader demoUrl="https://demo.example.org" />
        <ShowcaseBanner />
        <EnterpriseStatusView status={statusWith()} />
        <EnterpriseEntrances user={null} />
      </>,
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

describe("HUB-02 intestazione e banner", () => {
  it("il banner dice non HA, dati fittizi e azzeramento settimanale senza backup (Q-624)", () => {
    render(<ShowcaseBanner />);
    const banner = screen.getByRole("note");
    expect(banner).toHaveTextContent(/non è ad alta disponibilità \(HA\)/);
    expect(banner).toHaveTextContent(/solo dati fittizi/);
    expect(banner).toHaveTextContent(/ogni settimana, senza backup/);
  });

  it("con LH_HUB_DEMO_URL valida c'è il ritorno alla demo, nella stessa scheda", () => {
    render(<EnterpriseHubHeader demoUrl="https://demo.example.org" />);
    const back = screen.getByRole("link", { name: /Torna alla demo/ });
    expect(back).toHaveAttribute("href", "https://demo.example.org");
    expect(back).toHaveAttribute("rel", "noopener");
    expect(back).not.toHaveAttribute("target");
    expect(back).toHaveAccessibleName(`${t.hubEnterprise.backToDemo} ${t.hubEnterprise.backToDemoNote}`);
  });

  it("senza URL di ritorno il collegamento non c'è", () => {
    render(<EnterpriseHubHeader demoUrl={null} />);
    expect(screen.queryByRole("link", { name: /Torna alla demo/ })).toBeNull();
    expect(screen.getByRole("link", { name: /Repository/ })).toBeInTheDocument();
  });

  it("la pagina composta ha un solo H1 e titoli in ordine (H1 → H2 → H3)", () => {
    const { container } = render(
      <>
        <EnterpriseHubHeader demoUrl={null} />
        <ShowcaseBanner />
        <EnterpriseStatusView status={statusWith()} />
        <EnterpriseEntrances user={null} />
      </>,
    );
    expect(container.querySelectorAll("h1")).toHaveLength(1);
    const levels = Array.from(container.querySelectorAll("h1,h2,h3")).map((h) => Number(h.tagName[1]));
    levels.reduce((prev, cur) => {
      expect(cur - prev).toBeLessThanOrEqual(1);
      return cur;
    });
    // Ogni collegamento ha un nome accessibile.
    for (const link of screen.getAllByRole("link")) expect(link).toHaveAccessibleName();
  });
});

describe("HUB-02 pannello stato", () => {
  it("loading: scheletro della forma finale", () => {
    render(<EnterpriseStatusSkeleton />);
    expect(screen.getByTestId("status-skeleton")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("heading", { name: t.hubEnterprise.statusTitle })).toBeInTheDocument();
  });

  it("tutto attivo: 6 tessere, cms «non installato», tempi di risposta, nessun riquadro", () => {
    render(<EnterpriseStatusView status={statusWith()} />);
    expect(document.querySelectorAll("[data-testid^='tile-']")).toHaveLength(6);
    expect(screen.getByTestId("tile-cms")).toHaveAttribute("data-state", "NOT_INSTALLED");
    expect(screen.getByTestId("tile-cms")).toHaveTextContent("non installato");
    expect(screen.getByTestId("tile-hub")).toHaveTextContent("12 ms");
    expect(screen.getByTestId("tile-web")).toHaveTextContent("Attivo");
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
    expect(screen.queryByRole("button", { name: /Accendi la demo/ })).toBeNull();
  });

  it("degraded: riquadro con i componenti non attivi e riprova automatica ogni 5 s fino a 90 s", () => {
    vi.useFakeTimers();
    render(<EnterpriseStatusView status={statusWith({ idp: "DOWN", kafka: "DOWN" })} />);
    expect(screen.getByRole("status")).toHaveTextContent("Alcuni componenti non sono attivi: Accesso, Kafka.");
    expect(screen.getByTestId("tile-idp")).toHaveTextContent("Non risponde");
    act(() => {
      vi.advanceTimersByTime(5_000);
    });
    expect(refresh).toHaveBeenCalledTimes(1);
    act(() => {
      vi.advanceTimersByTime(200_000);
    });
    expect(refresh).toHaveBeenCalledTimes(18);
    expect(screen.getByTestId("auto-retry")).toHaveTextContent(t.hubEnterprise.degradedGaveUp);
  });

  it("error: hub irraggiungibile, riquadro con Riprova e infrastruttura non verificabile", () => {
    render(<EnterpriseStatusView status={statusWith({ hub: "DOWN", db: "UNKNOWN", kafka: "UNKNOWN" })} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent(t.hubEnterprise.statusError);
    expect(screen.getByTestId("tile-db")).toHaveTextContent("Non verificabile");
    fireEvent.click(within(alert).getByRole("button", { name: /Riprova/ }));
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("«Aggiorna» rifà il rendering della pagina, senza endpoint dedicati", () => {
    render(<EnterpriseStatusView status={statusWith()} />);
    fireEvent.click(screen.getByRole("button", { name: /Aggiorna/ }));
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("il pannello server usa la funzione di caricamento ricevuta", async () => {
    const load = vi.fn(async () => statusWith({ db: "DOWN" }));
    render(await EnterpriseStatusPanel({ load }));
    expect(load).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("tile-db")).toHaveAttribute("data-state", "DOWN");
  });
});

describe("layout del Demo Hub", () => {
  it("demo: banner «Ambiente dimostrativo» invariato", () => {
    vi.stubEnv("LH_PROFILE", "");
    render(<HubLayout>contenuto</HubLayout>);
    expect(screen.getByText(t.app.demoBanner)).toBeInTheDocument();
  });

  it("enterprise: niente banner «nessuna autenticazione» né keep-alive verso /api/demo/wake", () => {
    vi.useFakeTimers();
    vi.stubEnv("LH_PROFILE", "enterprise");
    render(<HubLayout>contenuto</HubLayout>);
    expect(screen.getByText("contenuto")).toBeInTheDocument();
    expect(screen.queryByText(t.app.demoBanner)).toBeNull();
    act(() => {
      vi.advanceTimersByTime(10 * 60_000);
    });
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
