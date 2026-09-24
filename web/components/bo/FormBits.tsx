"use client";

import { Card, CardBody } from "@/components/ui/card";

// Pezzi dei form del backoffice (docs/08 §3.2): sezione a scheda, campo con etichetta e suggerimento, stile input.

export const INPUT =
  "w-full rounded border border-[var(--color-bo-border)] bg-white px-2 py-1.5 text-sm disabled:bg-[var(--color-bo-bg)] disabled:text-[var(--color-bo-ink-2)]";

export function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card>
      <CardBody className="space-y-3 pt-4">
        <h3 className="text-sm font-semibold">{title}</h3>
        {children}
      </CardBody>
    </Card>
  );
}

export function Field({
  label,
  hint,
  group = false,
  children,
}: {
  label: string;
  hint?: string;
  /** Più controlli (es. caselle): un gruppo con didascalia invece di un'etichetta annidata. */
  group?: boolean;
  children: React.ReactNode;
}) {
  const body = (
    <>
      <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">{label}</span>
      {children}
      {hint ? <span className="mt-1 block text-xs text-[var(--color-bo-ink-2)]">{hint}</span> : null}
    </>
  );
  return group ? (
    <div role="group" aria-label={label} className="block text-sm">
      {body}
    </div>
  ) : (
    <label className="block text-sm">{body}</label>
  );
}
