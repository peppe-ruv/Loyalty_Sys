import { afterEach, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { LogoutButton } from "./LogoutButton";

// «Esci» (docs/07 §4-bis): modulo a pagina intera verso POST /api/auth/logout con il token CSRF in un campo; nessuna
// fetch, quindi nessuna risposta (e nessun ID token) arriva al JavaScript. Stato in corso: pulsante disabilitato.

afterEach(() => {
  delete (document as unknown as Record<string, unknown>).cookie;
});

it("modulo POST verso /api/auth/logout con il token CSRF letto all'invio, poi «Uscita…» disabilitato", async () => {
  Object.defineProperty(document, "cookie", { configurable: true, get: () => "__Host-lh_csrf=tok-9" });
  const { container } = render(<LogoutButton />);
  const form = container.querySelector("form")!;
  expect(form.getAttribute("method")).toBe("post");
  expect(form.getAttribute("action")).toBe("/api/auth/logout");
  // jsdom non naviga: si blocca solo l'invio vero, dopo i gestori del componente.
  form.addEventListener("submit", (e) => e.preventDefault());
  fireEvent.submit(form);
  expect((container.querySelector('input[name="csrf"]') as HTMLInputElement).value).toBe("tok-9");
  expect(await screen.findByRole("button", { name: "Uscita…" })).toBeDisabled();
});
