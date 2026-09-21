import Link from "next/link";
import { ArrowRight, Github } from "lucide-react";
import { StatusPanel } from "@/components/hub/StatusPanel";
import { Card, CardBody } from "@/components/ui/card";
import { it } from "@/lib/i18n/it";
import { BACKOFFICE_PERSONAS } from "@/lib/persona/personas";

// HUB-01 — Demo Hub (docs/07 §8). Feature: F-DEMO-01. Chiama: /api/demo/status, /api/demo/wake.

const REPO_URL = "https://github.com/peppe-ruv/Loyalty_Sys";

const PATH_STEPS = [
  "Accendi la demo e attendi che i servizi diventino verdi.",
  "Backoffice → Scenari guidati: manda un acquisto e guardalo diventare punti.",
  "Backoffice → Flusso live: osserva le azioni scorrere nel rail eventi.",
  "Portale → Tessera: vedi il saldo salire con il count-up.",
  "Backoffice → Console demo: reset dei dati per ricominciare.",
];

export default function DemoHubPage() {
  return (
    <div className="mx-auto min-h-dvh max-w-5xl px-4 pb-24 pt-10">
      <header className="mb-8">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">{it.app.name}</h1>
            <p className="mt-2 max-w-2xl text-[var(--color-bo-ink-2)]">{it.app.pitch}</p>
          </div>
          <a
            href={REPO_URL}
            className="inline-flex shrink-0 items-center gap-2 rounded-md border border-[var(--color-bo-border)] px-3 py-2 text-sm hover:bg-[var(--color-bo-surface)]"
          >
            <Github className="h-4 w-4" /> {it.app.repo}
          </a>
        </div>
      </header>

      <div className="mb-8">
        <StatusPanel />
      </div>

      <section className="mb-8">
        <h2 className="mb-3 text-lg font-semibold">{it.hub.entrancesTitle}</h2>
        <div className="grid gap-4 md:grid-cols-2">
          <Card>
            <CardBody className="pt-4">
              <div className="mb-3 flex items-center justify-between">
                <h3 className="font-semibold">{it.hub.backoffice}</h3>
                <Link href="/backoffice" className="inline-flex items-center gap-1 text-sm text-[var(--color-bo-accent)]">
                  Entra <ArrowRight className="h-4 w-4" />
                </Link>
              </div>
              <ul className="space-y-2">
                {BACKOFFICE_PERSONAS.map((p) => (
                  <li key={p.username} className="text-sm">
                    <span className="font-medium">{p.displayName}</span>{" "}
                    <span className="rounded bg-[var(--color-bo-bg)] px-1.5 py-0.5 text-xs font-semibold text-[var(--color-bo-ink-2)]">
                      {p.role}
                    </span>
                    <span className="block text-[var(--color-bo-ink-2)]">{p.summary}</span>
                  </li>
                ))}
              </ul>
            </CardBody>
          </Card>

          <Card>
            <CardBody className="pt-4">
              <div className="mb-3 flex items-center justify-between">
                <h3 className="font-semibold">{it.hub.portal}</h3>
                <Link href="/portal" className="inline-flex items-center gap-1 text-sm text-[var(--color-bo-accent)]">
                  Entra <ArrowRight className="h-4 w-4" />
                </Link>
              </div>
              <p className="text-sm text-[var(--color-bo-ink-2)]">{it.shell.portalEmpty}</p>
            </CardBody>
          </Card>
        </div>
      </section>

      <section className="mb-8">
        <h2 className="mb-3 text-lg font-semibold">{it.hub.pathTitle}</h2>
        <ol className="space-y-2">
          {PATH_STEPS.map((step, i) => (
            <li key={i} className="flex gap-3 text-sm">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[var(--color-bo-accent)] text-xs font-bold text-white">
                {i + 1}
              </span>
              <span>{step}</span>
            </li>
          ))}
        </ol>
      </section>

      <footer className="fixed inset-x-0 bottom-0 border-t border-[var(--color-bo-border)] bg-[var(--color-bo-surface)]/95 px-4 py-2 text-center text-xs text-[var(--color-bo-ink-2)] backdrop-blur">
        {it.app.demoBanner}
      </footer>
    </div>
  );
}
