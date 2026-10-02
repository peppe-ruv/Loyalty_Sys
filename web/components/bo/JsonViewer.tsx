// JsonViewer (docs/08 §3.6): JSON indentato in monospazio, scorrevole, selezionabile per la copia.

export function JsonViewer({ value, label }: { value: unknown; label?: string }) {
  let text: string;
  try {
    text = JSON.stringify(value ?? null, null, 2);
  } catch {
    text = String(value);
  }
  return (
    <pre
      aria-label={label}
      tabIndex={0}
      className="max-h-80 overflow-auto rounded border border-[var(--color-bo-border)] bg-white p-3 font-mono text-xs leading-5 text-[var(--color-bo-ink)]"
    >
      {text}
    </pre>
  );
}
