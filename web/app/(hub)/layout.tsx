import { KeepAlive } from "@/components/shared/KeepAlive";
import { isEnterprise } from "@/lib/auth/config";
import { it } from "@/lib/i18n/it";

// Layout del Demo Hub (HUB-01, docs/07 §8): keep-alive gentile come nelle altre aree (F-DEMO-07) e banner fisso
// "Ambiente dimostrativo" (docs/07 §4).
// HUB-02 (profilo enterprise, ADR-049): niente keep-alive (non ci sono servizi gratuiti da tenere svegli, e
// `/api/demo/wake` è della demo) e niente banner «nessuna autenticazione», falso con il login reale: il banner della
// vetrina sta nella pagina. La pagina è dinamica, quindi il profilo si legge a ogni richiesta.
export default function HubLayout({ children }: { children: React.ReactNode }) {
  if (isEnterprise()) return <>{children}</>;
  return (
    <KeepAlive>
      {children}
      <footer className="fixed inset-x-0 bottom-0 border-t border-[var(--color-bo-border)] bg-[var(--color-bo-surface)]/95 px-4 py-2 text-center text-xs text-[var(--color-bo-ink-2)] backdrop-blur">
        {it.app.demoBanner}
      </footer>
    </KeepAlive>
  );
}
