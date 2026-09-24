"use client";

import { useEffect, useRef } from "react";

// Gratta e vinci (docs/09 §PT-06, meccanica SCRATCH): una patina da grattare sopra l'esito già deciso dal server;
// al 55 % scoperto si rivela tutto. `children` è l'esito, visibile sotto la patina.
const REVEAL_AT = 0.55;

export function ScratchCard({ children, onRevealed, disabled }: { children: React.ReactNode; onRevealed: () => void; disabled?: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const done = useRef(false);

  useEffect(() => {
    const el = canvas.current;
    const ctx = el?.getContext("2d");
    if (!el || !ctx) return;
    el.width = el.offsetWidth * 2;
    el.height = el.offsetHeight * 2;
    const g = ctx.createLinearGradient(0, 0, el.width, el.height);
    g.addColorStop(0, "#c9d3dc");
    g.addColorStop(1, "#9fb0bf");
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, el.width, el.height);
    ctx.fillStyle = "#0e1b2c";
    ctx.font = `600 ${Math.round(el.height / 7)}px system-ui, sans-serif`;
    ctx.textAlign = "center";
    ctx.fillText("Gratta qui", el.width / 2, el.height / 2 + el.height / 20);
    ctx.globalCompositeOperation = "destination-out";
  }, []);

  function scratch(e: React.PointerEvent<HTMLCanvasElement>) {
    if (disabled || done.current || (e.buttons === 0 && e.pointerType === "mouse")) return;
    const el = e.currentTarget;
    const ctx = el.getContext("2d");
    if (!ctx) return;
    const rect = el.getBoundingClientRect();
    const x = ((e.clientX - rect.left) / rect.width) * el.width;
    const y = ((e.clientY - rect.top) / rect.height) * el.height;
    ctx.beginPath();
    ctx.arc(x, y, el.width / 12, 0, Math.PI * 2);
    ctx.fill();
    if (cleared(ctx, el) >= REVEAL_AT) {
      done.current = true;
      ctx.clearRect(0, 0, el.width, el.height);
      onRevealed();
    }
  }

  return (
    <div className="relative mx-auto aspect-[3/2] w-full max-w-[320px] overflow-hidden rounded-2xl bg-white shadow-md ring-1 ring-black/5">
      <div className="flex h-full items-center justify-center p-4 text-center">{children}</div>
      <canvas
        ref={canvas}
        aria-hidden
        onPointerDown={scratch}
        onPointerMove={scratch}
        className="absolute inset-0 h-full w-full touch-none"
        style={{ cursor: disabled ? "default" : "crosshair" }}
      />
    </div>
  );
}

/** Quota trasparente, campionata su una griglia (niente scansione completa a ogni movimento). */
function cleared(ctx: CanvasRenderingContext2D, el: HTMLCanvasElement): number {
  const data = ctx.getImageData(0, 0, el.width, el.height).data;
  let clear = 0;
  let total = 0;
  const step = 16;
  for (let y = 0; y < el.height; y += step) {
    for (let x = 0; x < el.width; x += step) {
      total++;
      if (data[(y * el.width + x) * 4 + 3] === 0) clear++;
    }
  }
  return total === 0 ? 0 : clear / total;
}
