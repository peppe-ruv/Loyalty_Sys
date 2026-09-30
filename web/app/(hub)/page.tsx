import Link from "next/link";
import { ArrowRight, Github } from "lucide-react";
import { Entrances } from "@/components/hub/Entrances";
import { EnterpriseShowcaseLink } from "@/components/hub/EnterpriseShowcaseLink";
import { StatusPanel } from "@/components/hub/StatusPanel";
import { REPO_URL, RECOMMENDED_PATH } from "@/lib/hub/links";
import { it } from "@/lib/i18n/it";

// HUB-01 — Demo Hub (docs/07 §8). Feature: F-DEMO-01, F-DEMO-07 (keep-alive nel layout del gruppo).
// Chiama: /api/demo/status, /api/demo/wake, /api/persona, member GET /v1/demo/personas.
// F2-DIST-09, ADR-049: il pulsante verso la vetrina Enterprise legge LH_HUB_ENTERPRISE_URL a runtime, quindi la pagina
// non può essere prerenderizzata al build (altrimenti il valore resterebbe quello del build).
export const dynamic = "force-dynamic";

export default function DemoHubPage() {
  return (
    <div className="mx-auto min-h-dvh max-w-5xl px-4 pb-24 pt-10">
      <header className="mb-8">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">{it.app.name}</h1>
            <p className="mt-2 max-w-2xl text-[var(--color-bo-ink-2)]">{it.app.pitch}</p>
          </div>
          <div className="flex shrink-0 flex-wrap items-start gap-3">
            <EnterpriseShowcaseLink />
            <a
              href={REPO_URL}
              className="inline-flex shrink-0 items-center gap-2 rounded-md border border-[var(--color-bo-border)] px-3 py-2 text-sm hover:bg-[var(--color-bo-surface)]"
            >
              <Github className="h-4 w-4" /> {it.app.repo}
            </a>
          </div>
        </div>
      </header>

      <div className="mb-8">
        <StatusPanel />
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
