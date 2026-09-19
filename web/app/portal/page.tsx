import { it } from "@/lib/i18n/it";

// Portale — home (shell M0.6). Le schermate PT-xx arrivano da M1 (docs/09).
export default function PortalHome() {
  return (
    <div className="rounded-2xl border border-[var(--color-bo-border)] bg-white p-5">
      <h1 className="mb-2 text-lg font-semibold text-[var(--color-pt-night)]">Portale membri</h1>
      <p className="text-sm text-[var(--color-bo-ink-2)]">{it.shell.portalEmpty}</p>
    </div>
  );
}
