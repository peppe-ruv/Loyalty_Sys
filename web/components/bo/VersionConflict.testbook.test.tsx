import { expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { VersionConflict } from "./VersionConflict";

// Testbook TB-WEB §VER (avviso): docs/08 §3.2 — «Qualcun altro ha modificato: ricarica / sovrascrivi».

it("[TB-WEB-VER-011] avviso con «Qualcun altro ha modificato» e le due scelte Ricarica / Sovrascrivi", () => {
  render(<VersionConflict what="la campagna" onReload={vi.fn()} onOverwrite={vi.fn()} />);
  expect(screen.getByText(/Qualcun altro ha modificato la campagna/)).toBeInTheDocument();
  expect(screen.getAllByRole("button").map((b) => b.textContent)).toEqual(["Ricarica", "Sovrascrivi"]);
});

it("[TB-WEB-VER-012] Ricarica → rilegge l'oggetto (onReload), non sovrascrive", () => {
  const onReload = vi.fn();
  const onOverwrite = vi.fn();
  render(<VersionConflict what="il premio" onReload={onReload} onOverwrite={onOverwrite} />);
  fireEvent.click(screen.getByRole("button", { name: "Ricarica" }));
  expect([onReload.mock.calls.length, onOverwrite.mock.calls.length]).toEqual([1, 0]);
});

it("[TB-WEB-VER-013] Sovrascrivi → rimanda le modifiche (onOverwrite), non ricarica", () => {
  const onReload = vi.fn();
  const onOverwrite = vi.fn();
  render(<VersionConflict what="il premio" onReload={onReload} onOverwrite={onOverwrite} />);
  fireEvent.click(screen.getByRole("button", { name: "Sovrascrivi" }));
  expect([onReload.mock.calls.length, onOverwrite.mock.calls.length]).toEqual([0, 1]);
});

it("[TB-WEB-VER-014] durante il salvataggio (busy) → entrambe le scelte disabilitate", () => {
  render(<VersionConflict what="il concorso" busy onReload={vi.fn()} onOverwrite={vi.fn()} />);
  expect(screen.getAllByRole("button").every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
});
