"use client";

// Coriandoli leggeri in CSS (PT-06 esito WIN): nessuna libreria; con movimento ridotto le animazioni si azzerano.
const COLORS = ["#1fb98f", "#7a5cfa", "#ffb547", "#2a78d6", "#eb6834"];

export function Confetti({ pieces = 40 }: { pieces?: number }) {
  return (
    <div aria-hidden className="pointer-events-none absolute inset-x-0 top-0 h-0 overflow-visible">
      {Array.from({ length: pieces }, (_, i) => {
        const left = (i * 37) % 100;
        const drift = ((i * 53) % 120) - 60;
        const delay = (i % 10) * 0.08;
        return (
          <span
            key={i}
            className="absolute block h-3 w-1.5 rounded-sm"
            style={{
              left: `${left}%`,
              background: COLORS[i % COLORS.length],
              animation: `lh-confetti-fall ${1.6 + (i % 5) * 0.2}s ease-in ${delay}s forwards`,
              ["--lh-drift" as string]: `${drift}px`,
              opacity: 0,
            }}
          />
        );
      })}
    </div>
  );
}
