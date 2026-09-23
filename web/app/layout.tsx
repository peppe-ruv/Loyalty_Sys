import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";
import { Providers } from "./providers";

// Font (docs/07 §5.1) serviti dal repository con next/font/local: la build non dipende più da Google Fonts
// (download fallito due volte in CI). File woff2 latin variabili, licenza SIL OFL 1.1 (vedi app/fonts/README.md).
const ui = localFont({
  src: "./fonts/HankenGrotesk-latin.woff2",
  weight: "400 700",
  variable: "--font-ui-loaded",
  display: "swap",
});
const display = localFont({
  src: "./fonts/BricolageGrotesque-latin.woff2",
  weight: "600 800",
  variable: "--font-display-loaded",
  display: "swap",
});
const mono = localFont({
  src: "./fonts/JetBrainsMono-latin.woff2",
  weight: "100 800",
  variable: "--font-mono-loaded",
  display: "swap",
});

export const metadata: Metadata = {
  title: "Loyalty Hub",
  description: "Piattaforma loyalty open source ed event-driven — ambiente dimostrativo.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="it" className={`${ui.variable} ${display.variable} ${mono.variable}`}>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
