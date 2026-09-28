import { useRef, useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
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
    // Il focus va sull'opzione meno distruttiva: Esc e poi Invio non buttano la bozza.
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Continua a modificare" }));
    expect(confirm).toHaveTextContent("Le modifiche andranno perse.");
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

  it("il focus va al foglio all'apertura e il dialogo prende il nome dal titolo", () => {
    renderSheet(false);
    expect(document.activeElement).toBe(screen.getByRole("dialog", { name: "Nuova azione" }));
    expect(screen.getByRole("heading", { level: 2, name: "Nuova azione" })).toBeInTheDocument();
  });

  it("la frase della conferma si può adattare al contenuto", () => {
    render(
      <SideSheet open title="Azioni ammesse" onClose={vi.fn()} dirty confirmText="Le modifiche all'elenco andranno perse.">
        <CancelButton />
      </SideSheet>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Annulla" }));
    expect(screen.getByRole("alertdialog")).toHaveTextContent("Le modifiche all'elenco andranno perse.");
  });

  it("Tab e Maiusc+Tab restano dentro il foglio", () => {
    renderSheet(false);
    const dialog = screen.getByRole("dialog");
    const close = within(dialog).getByRole("button", { name: "Chiudi" });
    const cancel = within(dialog).getByRole("button", { name: "Annulla" });
    cancel.focus();
    fireEvent.keyDown(cancel, { key: "Tab" });
    expect(document.activeElement).toBe(close);
    fireEvent.keyDown(close, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(cancel);
  });

  it("alla chiusura il focus torna a chi aveva aperto il foglio", () => {
    function Host() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>
            Apri
          </button>
          <SideSheet open={open} title="Dettaglio" onClose={() => setOpen(false)}>
            <p>Contenuto</p>
          </SideSheet>
        </>
      );
    }
    render(<Host />);
    const opener = screen.getByRole("button", { name: "Apri" });
    opener.focus();
    fireEvent.click(opener);
    expect(document.activeElement).toBe(screen.getByRole("dialog"));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("se chi aveva aperto il foglio non c'è più, il focus va su returnFocusRef", () => {
    function Host() {
      const [open, setOpen] = useState(false);
      const fallback = useRef<HTMLInputElement>(null);
      return (
        <>
          <input ref={fallback} aria-label="Cerca" />
          {!open ? (
            <button type="button" onClick={() => setOpen(true)}>
              Apri
            </button>
          ) : null}
          <SideSheet open={open} title="Dettaglio" onClose={() => setOpen(false)} returnFocusRef={fallback}>
            <p>Contenuto</p>
          </SideSheet>
        </>
      );
    }
    render(<Host />);
    const opener = screen.getByRole("button", { name: "Apri" });
    opener.focus();
    fireEvent.click(opener);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(document.activeElement).toBe(screen.getByRole("textbox", { name: "Cerca" }));
  });
});
