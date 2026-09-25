import { expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { PersonaProvider } from "./PersonaContext";
import { Can } from "./Can";
import type { Role } from "@/lib/persona/personas";

// Testbook TB-WEB §CAN: componente <Can> (docs/07 §4, §6 "Forbidden"; docs/08 §2 ultimo capoverso).
// Oracolo: nasconde se l'azione non ha senso, oppure la disabilita con tooltip col ruolo richiesto.

function renderAs(role: Role, ui: React.ReactNode) {
  return render(<PersonaProvider value={{ username: "u", displayName: "U", role }}>{ui}</PersonaProvider>);
}

it("[TB-WEB-CAN-001] ruolo abilitato (LEGAL × object.approve, modo disable) → azione visibile, senza tooltip di divieto", () => {
  renderAs(
    "LEGAL",
    <Can capability="object.approve" mode="disable">
      <button>Approva</button>
    </Can>,
  );
  const button = screen.getByRole("button", { name: "Approva" });
  expect(button).toBeEnabled();
  expect(button.closest("[title]")).toBeNull();
});

it("[TB-WEB-CAN-002] ruolo non abilitato, modo hide (default) → azione nascosta", () => {
  renderAs(
    "ANALYST",
    <Can capability="member.anonymize">
      <button>Anonimizza</button>
    </Can>,
  );
  expect(screen.queryByRole("button", { name: "Anonimizza" })).toBeNull();
});

it("[TB-WEB-CAN-003] ruolo non abilitato, modo disable → azione visibile con tooltip «Richiede … LEGAL»", () => {
  renderAs(
    "MARKETING",
    <Can capability="object.approve" mode="disable">
      <button>Approva</button>
    </Can>,
  );
  const button = screen.getByRole("button", { name: "Approva" });
  const tip = button.closest("[title]")?.getAttribute("title") ?? "";
  expect(tip).toMatch(/Richiede/);
  expect(tip).toContain("LEGAL");
});

it("[TB-WEB-CAN-004] ruolo non abilitato, modo disable → il controllo è disabilitato (non attivabile da tastiera)", () => {
  renderAs(
    "MARKETING",
    <Can capability="object.approve" mode="disable">
      <button>Approva</button>
    </Can>,
  );
  // docs/07 §4 e §6: l'azione è "disabilitata". Un pulsante solo avvolto in pointer-events:none resta attivabile con
  // Tab + Invio: deve risultare disabled (o aria-disabled).
  const button = screen.getByRole("button", { name: "Approva" });
  const disabled = (button as HTMLButtonElement).disabled || button.getAttribute("aria-disabled") === "true";
  expect(disabled).toBe(true);
});
