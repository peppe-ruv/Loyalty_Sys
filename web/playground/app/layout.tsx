import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import './globals.css';
import { ThemeToggle } from './theme-toggle';

export const metadata: Metadata = {
  title: 'Loyalty Hub — playground',
  description:
    'I pattern del backoffice e il motore decisionale di Loyalty Hub su dati finti: nessun backend, nessun dato personale.',
};

const SEZIONI = [
  { href: '/', label: 'Panoramica' },
  { href: '/pattern', label: 'Pattern' },
  { href: '/decisioni', label: 'Decisioni' },
  { href: '/token', label: 'Token' },
];

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="it">
      <body>
        <div className="pg-shell">
          <header className="pg-header">
            <a className="pg-brand" href="/">
              Loyalty <span>Hub</span> — playground
            </a>
            <nav className="pg-nav" aria-label="Sezioni">
              {SEZIONI.map((sezione) => (
                <a key={sezione.href} href={sezione.href}>
                  {sezione.label}
                </a>
              ))}
            </nav>
            <ThemeToggle />
          </header>
          <main>{children}</main>
          <footer className="pg-footer">
            <p>
              Dati inventati, nessun backend e nessun dato personale: il playground gira interamente nel browser. Il
              codice è su{' '}
              <a href="https://github.com/peppe-ruv/Loyalty_Sys">github.com/peppe-ruv/Loyalty_Sys</a>, licenza
              Apache-2.0.
            </p>
          </footer>
        </div>
      </body>
    </html>
  );
}
