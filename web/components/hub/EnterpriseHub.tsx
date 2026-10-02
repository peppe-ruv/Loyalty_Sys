import Link from "next/link";
import { Suspense } from "react";
import { ArrowLeft, ArrowRight, Github, Info, LogIn, Users } from "lucide-react";
import { Card, CardBody } from "@/components/ui/card";
import { serviceBaseUrl } from "@/lib/api/services";
import { getAuthConfig } from "@/lib/auth/config";
import type { SessionUser } from "@/lib/auth/sessionStore";
import { cn } from "@/lib/cn";
import { formatTime } from "@/lib/format/dates";
import {
  cachedEnterpriseStatus,
  notUp,
  statusView,
  type EnterpriseStatus,
  type StatusTile,
  type TileState,
} from "@/lib/hub/enterpriseStatus";
import { REPO_URL } from "@/lib/hub/links";
import { it } from "@/lib/i18n/it";
import { AutoRetryNote, RefreshStatusButton } from "./RefreshStatus";

// HUB-02 — Demo Hub nel profilo enterprise (docs/18 §5, ADR-049, F2-DIST-09, M8.14 V5). Server component: nessuna
// chiamata a `/api/persona` né a `/v1/demo/personas`, nessun «Accendi la demo» (niente servizi addormentati nella
// vetrina). Al posto delle persone il login OIDC del BFF (`/api/auth/login`); il portale membri resta senza ingresso
// finché Q-619 non lo apre. Il ritorno alla demo viene da `LH_HUB_DEMO_URL`, validata lato server (`demoHubUrl`).
// SPEC-GAP: Q-641 — «stato migrazioni» di docs/18 §5 non mostrato: nessuna API lo espone (la tessera `hub` è attiva
// solo dopo le migrazioni applicate all'avvio).

const t = it.hubEnterprise;

/** Login del BFF con ritorno al backoffice (docs/07 §4-bis): il percorso è validato da `safeReturnTo`. */
export const BACKOFFICE_LOGIN_HREF = `/api/auth/login?returnTo=${encodeURIComponent("/backoffice")}`;


export function EnterpriseHubHeader({ demoUrl }: { demoUrl: string | null }) {
  return (
    <header className="mb-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">{it.app.name}</h1>
          <p className="mt-1 text-sm font-semibold uppercase tracking-wide text-[var(--color-bo-accent)]">{t.subtitle}</p>
          <p className="mt-2 max-w-2xl text-[var(--color-bo-ink-2)]">{t.pitch}</p>
        </div>
        <div className="flex min-w-0 flex-wrap items-start gap-3">
          {demoUrl !== null ? (
            <a
              href={demoUrl}
              rel="noopener"
              className="inline-flex shrink-0 items-center gap-2 rounded-md border border-[var(--color-bo-border)] px-3 py-2 text-sm font-medium hover:bg-[var(--color-bo-surface)]"
            >
              <ArrowLeft className="h-4 w-4" aria-hidden /> {t.backToDemo}{" "}
              <span className="sr-only">
                {t.backToDemoNote}
              </span>
            </a>
          ) : null}
          <a
            href={REPO_URL}
            className="inline-flex shrink-0 items-center gap-2 rounded-md border border-[var(--color-bo-border)] px-3 py-2 text-sm hover:bg-[var(--color-bo-surface)]"
          >
            <Github className="h-4 w-4" aria-hidden /> {it.app.repo}
          </a>
        </div>
      </div>
    </header>
  );
}

/**
 * Banner della vetrina (Q-624, Q-662, Q-663): non HA, solo dati fittizi, accesa su richiesta, azzeramento senza backup.
 * SPEC-GAP: Q-640 — compare in ogni HUB-02 `enterprise`, perché nessuna variabile distingue la vetrina da
 * un'installazione di chi adotta il prodotto (scelta conservativa: mai una vetrina senza avviso).
 */
export function ShowcaseBanner() {
  return (
    <p
      role="note"
      data-testid="showcase-banner"
      className="mb-8 flex items-start gap-2 rounded-md border border-[var(--color-state-waking)]/40 bg-[var(--color-state-waking)]/10 px-3 py-2 text-sm"
    >
      <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <span>{t.banner}</span>
    </p>
  );
}

const STATE_COLOR: Record<TileState, string> = {
  UP: "var(--color-state-up)",
  DOWN: "var(--color-state-down)",
  UNKNOWN: "var(--color-state-sleeping)",
  NOT_INSTALLED: "var(--color-state-sleeping)",
};

function Tile({ tile }: { tile: StatusTile }) {
  return (
    <li
      className={cn(
        "flex items-center justify-between rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] px-3 py-2.5",
        tile.state === "NOT_INSTALLED" && "border-dashed",
      )}
      data-testid={`tile-${tile.key}`}
      data-state={tile.state}
    >
      <span className="flex min-w-0 items-center gap-2">
        <span className="inline-block h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: STATE_COLOR[tile.state] }} aria-hidden />
        <span className="truncate text-sm font-medium">{t.tiles[tile.key]}</span>
        {tile.group === "role" ? (
          <span className="font-mono text-[10px] text-[var(--color-bo-ink-2)]">{tile.key}</span>
        ) : null}
      </span>
      <span className="tabular shrink-0 text-xs text-[var(--color-bo-ink-2)]">
        {tile.state === "UP" && tile.latencyMs != null ? `${tile.latencyMs} ms` : t.states[tile.state]}
      </span>
    </li>
  );
}

function TileGroup({ title, tiles }: { title: string; tiles: StatusTile[] }) {
  return (
    <div>
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">{title}</h3>
      <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {tiles.map((tile) => (
          <Tile key={tile.key} tile={tile} />
        ))}
      </ul>
    </div>
  );
}

function PanelShell({ children }: { children: React.ReactNode }) {
  return (
    <Card>
      <CardBody className="pt-4">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold">{t.statusTitle}</h2>
          <RefreshStatusButton />
        </div>
        {children}
      </CardBody>
    </Card>
  );
}

/** Loading (docs/07 §6): scheletro della forma finale, 4 tessere di ruolo e 2 d'infrastruttura. */
export function EnterpriseStatusSkeleton() {
  return (
    <Card aria-busy="true" data-testid="status-skeleton">
      <CardBody className="pt-4">
        <h2 className="mb-3 text-lg font-semibold">{t.statusTitle}</h2>
        <div className="space-y-4" aria-hidden>
          {[4, 2].map((n, g) => (
            <div key={g} className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
              {Array.from({ length: n }).map((_, i) => (
                <div key={i} className="h-11 animate-pulse rounded-md bg-[var(--color-bo-bg)]" />
              ))}
            </div>
          ))}
        </div>
      </CardBody>
    </Card>
  );
}

export function EnterpriseStatusView({ status }: { status: EnterpriseStatus }) {
  const view = statusView(status);
  const missing = notUp(status).map((key) => t.tiles[key]);
  return (
    <PanelShell>
      {view === "error" ? (
        <div
          role="alert"
          className="mb-3 rounded-md border border-[var(--color-state-down)]/30 bg-[var(--color-state-down)]/5 px-3 py-2 text-sm"
        >
          <p className="font-medium">{t.statusError}</p>
          <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">{t.statusErrorHint}</p>
          <div className="mt-2">
            <RefreshStatusButton label={t.retry} />
          </div>
        </div>
      ) : null}
      {view === "degraded" ? (
        <div
          role="status"
          className="mb-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
        >
          <p className="font-medium">{t.degraded(missing.join(", "))}</p>
          <AutoRetryNote />
        </div>
      ) : null}
      <div className="space-y-4">
        <TileGroup title={t.roles} tiles={status.tiles.filter((x) => x.group === "role")} />
        <TileGroup title={t.infra} tiles={status.tiles.filter((x) => x.group === "infra")} />
      </div>
      <p className="mt-3 text-xs text-[var(--color-bo-ink-2)]">{t.checkedAt(formatTime(status.checkedAt))}</p>
    </PanelShell>
  );
}

/** Stato dalla configurazione del processo: hub da `LH_SVC_INGESTION_URL`, IdP da `LH_OIDC_ISSUER` validato. */
export function loadEnterpriseStatus(): Promise<EnterpriseStatus> {
  let issuer: string | null = null;
  try {
    const cfg = getAuthConfig();
    if (cfg.mode === "enterprise") issuer = cfg.issuer.href;
  } catch {
    // Configurazione rifiutata (INSECURE_CONFIG): il server non dovrebbe nemmeno partire; qui l'IdP risulta giù.
  }
  return cachedEnterpriseStatus({ hubUrl: serviceBaseUrl("ingestion"), issuer });
}

export async function EnterpriseStatusPanel({
  load = loadEnterpriseStatus,
}: {
  load?: () => Promise<EnterpriseStatus>;
}) {
  return <EnterpriseStatusView status={await load()} />;
}

const entryClass =
  "inline-flex items-center gap-2 rounded-md bg-[var(--color-bo-accent)] px-4 py-2 text-sm font-semibold text-white hover:opacity-90";

/** Ingressi di HUB-02: login al posto della scelta persona; il portale non ha ingresso finché Q-619 non lo apre. */
export function EnterpriseEntrances({ user }: { user: Pick<SessionUser, "username" | "name"> | null }) {
  return (
    <section className="mb-8">
      <h2 className="mb-3 text-lg font-semibold">{t.entrancesTitle}</h2>
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardBody className="pt-4">
            <h3 className="mb-1 font-semibold">{t.backoffice}</h3>
            <p className="mb-3 text-sm text-[var(--color-bo-ink-2)]">{t.backofficeText}</p>
            {user ? (
              <>
                <p className="mb-2 text-xs text-[var(--color-bo-ink-2)]">{t.signedInAs(user.name ?? user.username)}</p>
                <Link href="/backoffice" className={entryClass}>
                  {t.openBackoffice} <ArrowRight className="h-4 w-4" aria-hidden />
                </Link>
              </>
            ) : (
              <a href={BACKOFFICE_LOGIN_HREF} className={entryClass}>
                <LogIn className="h-4 w-4" aria-hidden /> {t.login}
              </a>
            )}
            <p className="mt-3 text-xs text-[var(--color-bo-ink-2)]">{t.accounts}</p>
          </CardBody>
        </Card>
        {/* Empty (docs/07 §6): icona tenue e perché; nessun collegamento al portale (Q-619, ADR-049 punto 4). */}
        <div
          className="flex flex-col items-center justify-center rounded-md border border-dashed border-[var(--color-bo-border)] p-6 text-center"
          data-testid="portal-closed"
        >
          <Users className="mb-2 h-6 w-6 text-[var(--color-bo-ink-2)]/60" aria-hidden />
          <h3 className="text-sm font-semibold">{t.portal}</h3>
          <p className="text-sm">{t.portalClosed}</p>
          <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">{t.portalClosedHint}</p>
        </div>
      </div>
    </section>
  );
}

/** HUB-02 completa. `load` e `user` si iniettano nei test; in produzione arrivano dal server. */
export function EnterpriseHub({
  demoUrl,
  user,
  load,
}: {
  demoUrl: string | null;
  user: Pick<SessionUser, "username" | "name"> | null;
  load?: () => Promise<EnterpriseStatus>;
}) {
  return (
    <div className="mx-auto min-h-dvh max-w-5xl px-4 pb-16 pt-10">
      <EnterpriseHubHeader demoUrl={demoUrl} />
      <ShowcaseBanner />
      <div className="mb-8">
        <Suspense fallback={<EnterpriseStatusSkeleton />}>
          <EnterpriseStatusPanel load={load} />
        </Suspense>
      </div>
      <EnterpriseEntrances user={user} />
    </div>
  );
}
