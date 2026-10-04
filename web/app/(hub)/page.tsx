import Link from "next/link";
import { ArrowRight, Github } from "lucide-react";
import { Entrances } from "@/components/hub/Entrances";
import { EnterpriseShowcase } from "@/components/hub/EnterpriseShowcase";
import { EnterpriseHub } from "@/components/hub/EnterpriseHub";
import { StatusPanel } from "@/components/hub/StatusPanel";
import { isEnterprise } from "@/lib/auth/config";
import { getViewer } from "@/lib/auth/viewer";
import { testMode } from "@/lib/hub/testMode";
import { readMasterConsole } from "@/lib/hub/vetrinaMarkers";
import { demoHubUrl, REPO_URL, RECOMMENDED_PATH } from "@/lib/hub/links";
import { it } from "@/lib/i18n/it";

// HUB-01 — Demo Hub (docs/07 §8). Feature: F-DEMO-01, F-DEMO-07 (keep-alive nel layout del gruppo).
// Chiama: /api/demo/status, /api/demo/wake, /api/persona, member GET /v1/demo/personas.
// F2-DIST-09, ADR-049, Q-674: il riquadro «Modalità Enterprise» legge LH_HUB_ENTERPRISE_URL a runtime, quindi la pagina
// non può essere prerenderizzata al build (altrimenti il valore resterebbe quello del build).
// HUB-02 (ADR-049, M8.14 V5): nel profilo enterprise la stessa pagina rende il Demo Hub enterprise, letto a runtime.
export const dynamic = "force-dynamic";

async function EnterpriseHubPage() {
  // Utente della sessione del BFF, se c'è: solo nome e username arrivano alla pagina, mai token (regola 20).
  const viewer = await getViewer();
  const user = viewer.mode === "enterprise" ? viewer.user : null;
  // Sessione del membro (realm dei membri, V9b): solo username e nome arrivano alla pagina. Se il realm dei membri non
  // è configurato o la sessione manca, le schede dei membri restano quelle di ingresso (mai un errore dell'Hub).
  let memberUser: typeof user = null;
  try {
    const memberViewer = await getViewer("members");
    memberUser = memberViewer.mode === "enterprise" && memberViewer.user?.kind === "member" ? memberViewer.user : null;
  } catch {
    memberUser = null;
  }
  return <EnterpriseHub demoUrl={demoHubUrl(process.env)} user={user} memberUser={memberUser} testMode={testMode()} masterConsole={await readMasterConsole()} />;
}

export default function DemoHubPage() {
  if (isEnterprise()) return <EnterpriseHubPage />;
  return (
    <div className="mx-auto min-h-dvh max-w-5xl px-4 pb-24 pt-10">
      <header className="mb-8">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">{it.app.name}</h1>
            <p className="mt-2 max-w-2xl text-[var(--color-bo-ink-2)]">{it.app.pitch}</p>
          </div>
          <div className="flex min-w-0 flex-wrap items-start gap-3">
            <a
              href={REPO_URL}
              className="inline-flex shrink-0 items-center gap-2 rounded-md border border-[var(--color-bo-border)] px-3 py-2 text-sm hover:bg-[var(--color-bo-surface)]"
            >
              <Github className="h-4 w-4" /> {it.app.repo}
            </a>
          </div>
        </div>
      </header>

      {/* HUB-01, Q-674: il riquadro Enterprise sta accanto al pannello di stato (due colonne da md, impilato sul telefono). */}
      <div className="mb-8 grid items-start gap-4 md:grid-cols-2 md:[&>:only-child]:col-span-2">
        <StatusPanel />
        <EnterpriseShowcase />
      </div>

      <Entrances />

      <section className="mb-8">
        <h2 className="mb-3 text-lg font-semibold">{it.hub.pathTitle}</h2>
        <ol className="space-y-2">
          {RECOMMENDED_PATH.map((step, i) => (
            <li key={step.screen}>
              <Link
                href={step.href}
                className="group flex gap-3 rounded-md px-1 py-1 text-sm hover:bg-[var(--color-bo-surface)]"
              >
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[var(--color-bo-accent)] text-xs font-bold text-white">
                  {i + 1}
                </span>
                <span>
                  <span className="font-medium group-hover:underline">{step.title}</span>{" "}
                  <span className="font-mono text-xs text-[var(--color-bo-ink-2)]">{step.screen}</span>
                  <span className="block text-[var(--color-bo-ink-2)]">{step.text}</span>
                </span>
                <ArrowRight className="ml-auto mt-0.5 h-4 w-4 shrink-0 text-[var(--color-bo-ink-2)]" aria-hidden />
              </Link>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
