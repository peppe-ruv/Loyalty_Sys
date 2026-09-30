import { ArrowUpRight } from "lucide-react";
import { isEnterprise } from "@/lib/auth/config";
import { enterpriseShowcaseUrl } from "@/lib/hub/links";
import { it } from "@/lib/i18n/it";

// HUB-01, F2-DIST-09, ADR-049: pulsante verso la vetrina Enterprise (istanza separata). Server component: legge
// LH_HUB_ENTERPRISE_URL a runtime, mai NEXT_PUBLIC_. Nascosto senza URL valido e nel profilo enterprise (la vetrina
// non deve puntare a sé stessa). Stessa scheda, nessun cookie né stato condiviso.

const NOTE_ID = "hub-enterprise-note";

export function EnterpriseShowcaseLink({ env = process.env }: { env?: Readonly<Record<string, string | undefined>> }) {
  if (isEnterprise(env)) return null;
  const url = enterpriseShowcaseUrl(env);
  if (url === null) return null;
  return (
    <div>
      <a
        href={url}
        rel="noopener"
        aria-describedby={NOTE_ID}
        className="inline-flex items-center gap-2 whitespace-nowrap rounded-md bg-[var(--color-bo-accent)] px-3 py-2 text-sm font-medium text-white hover:opacity-90"
      >
        {it.hub.enterpriseCta}{" "}
        <ArrowUpRight className="h-4 w-4" aria-hidden />
        <span className="sr-only">{it.hub.enterpriseExternal}</span>
      </a>
      <p id={NOTE_ID} className="mt-1 max-w-56 text-xs text-[var(--color-bo-ink-2)]">
        {it.hub.enterpriseNote}
      </p>
    </div>
  );
}
