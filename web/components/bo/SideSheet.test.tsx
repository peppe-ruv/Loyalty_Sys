import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { SideSheet, useSideSheet } from "./SideSheet";

// Foglio laterale con conferma alla chiusura quando ci sono modifiche non salvate (BO-06 «2 · Quando», BO-09; Q-432).

function CancelButton() {
  const sheet = useSideSheet();
  return (
    <button type="button" onClick={() => sheet?.requestClose()}>
      Annulla
    </button>
  );
}

function renderSheet(dirty: boolean) {
  const onClose = vi.fn();
  render(
    <>
      <button type="button">Apri</button>
      <SideSheet open title="Nuova azione" onClose={onClose} dirty={dirty}>
        <CancelButton />
      </SideSheet>
    </>,
  );
  return onClose;
}

describe("SideSheet", () => {
  it("senza modifiche Esc, fondo e X chiudono subito", () => {
    const onClose = renderSheet(false);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getAllByRole("button", { name: "Chiudi" })[0]);
    expect(onClose).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("con modifiche chiede conferma: «Continua a modificare» resta aperto, «Chiudi senza salvare» chiude", () => {
    const onClose = renderSheet(true);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
    const confirm = screen.getByRole("alertdialog", { name: "Chiudere senza salvare?" });
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Chiudi senza salvare" }));
    fireEvent.click(screen.getByRole("button", { name: "Continua a modificare" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    expect(confirm).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Annulla" }));
    fireEvent.click(screen.getByRole("button", { name: "Chiudi senza salvare" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("Esc sulla conferma la chiude senza chiudere il foglio", () => {
    const onClose = renderSheet(true);
    fireEvent.click(screen.getAllByRole("button", { name: "Chiudi" })[1]);
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("il focus va al foglio all'apertura", () => {
    renderSheet(false);
    expect(document.activeElement).toBe(screen.getByRole("dialog"));
  });
});
