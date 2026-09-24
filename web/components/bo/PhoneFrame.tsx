// Cornice telefono per le anteprime fedeli del portale (docs/08 BO-18, BO-20): larghezza 390 px, fondo e colori del
// tema del portale; dentro si usano gli stessi componenti di components/shared/content.
export function PhoneFrame({ children, label }: { children: React.ReactNode; label?: string }) {
  return (
    <figure className="mx-auto w-[300px] sm:w-[340px]">
      <div className="rounded-[2.2rem] border-[10px] border-[var(--color-pt-night)] bg-[var(--color-pt-bg)] shadow-xl">
        <div className="mx-auto mt-1.5 h-1.5 w-16 rounded-full bg-[var(--color-pt-night)]/20" aria-hidden />
        <div className="max-h-[560px] min-h-[420px] overflow-y-auto px-3 pb-4 pt-3">
          <p className="mb-2 text-xs font-semibold text-[var(--color-pt-night)]">Club Aurora</p>
          {children}
        </div>
      </div>
      {label ? <figcaption className="mt-2 text-center text-xs text-[var(--color-bo-ink-2)]">{label}</figcaption> : null}
    </figure>
  );
}
