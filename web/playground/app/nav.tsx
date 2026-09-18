'use client';

import { usePathname } from 'next/navigation';

/**
 * La navigazione del playground.
 *
 * È un componente client per un motivo solo: segnare la sezione corrente con `aria-current="page"`.
 * Senza, la regola `.pg-nav a[aria-current='page']` in `globals.css` non scattava mai — una riga di
 * stile morta e, soprattutto, nessun modo per un lettore di schermo di dire dove ci si trova.
 */
export function Nav({ sezioni }: { sezioni: ReadonlyArray<{ href: string; label: string }> }) {
  const percorso = usePathname();
  return (
    <nav className="pg-nav" aria-label="Sezioni">
      {sezioni.map((sezione) => {
        const corrente = sezione.href === '/' ? percorso === '/' : percorso.startsWith(sezione.href);
        return (
          <a key={sezione.href} href={sezione.href} {...(corrente ? { 'aria-current': 'page' as const } : {})}>
            {sezione.label}
          </a>
        );
      })}
    </nav>
  );
}
