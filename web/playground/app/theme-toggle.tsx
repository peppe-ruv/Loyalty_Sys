'use client';

import { useEffect, useState } from 'react';

type Theme = 'system' | 'light' | 'dark';

/**
 * Tema chiaro/scuro (LG-03): di norma si segue il sistema, ma l'operatore può forzarlo.
 * La scelta vive in `data-theme` sulla radice, esattamente come nel backoffice: i token fanno
 * il resto e nessun componente sa che tema è attivo.
 */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>('system');

  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', theme);
  }, [theme]);

  return (
    <label className="pg-field">
      <span>Tema</span>
      <select
        value={theme}
        onChange={(event) => {
          setTheme(event.target.value as Theme);
        }}
      >
        <option value="system">Come il sistema</option>
        <option value="light">Chiaro</option>
        <option value="dark">Scuro</option>
      </select>
    </label>
  );
}
