import { afterEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { LogoutButton } from "./LogoutButton";

// «Esci» (docs/07 §4-bis): POST con il token CSRF; stato in corso e stato d'errore in linea (docs/07 §6).

afterEach(() => {
  vi.unstubAllGlobals();
  delete (document as unknown as Record<string, unknown>).cookie;
});

it("manda il token CSRF, mostra «Uscita…» e, se non riesce, l'errore con il pulsante di nuovo attivo", async () => {
  Object.defineProperty(document, "cookie", { configurable: true, get: () => "__Host-lh_csrf=tok-9" });
  let release!: (r: Response) => void;
  const fetchMock = vi.fn(() => new Promise<Response>((resolve) => (release = resolve)));
  vi.stubGlobal("fetch", fetchMock);

  render(<LogoutButton />);
  fireEvent.click(screen.getByRole("button", { name: "Esci" }));
  expect(await screen.findByRole("button", { name: "Uscita…" })).toBeDisabled();
  expect(fetchMock).toHaveBeenCalledWith("/api/auth/logout", { method: "POST", headers: { "X-LH-CSRF": "tok-9" }, cache: "no-store" });

  release(new Response(JSON.stringify({ code: "CSRF_REJECTED" }), { status: 403 }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Uscita non riuscita. Riprova.");
  await waitFor(() => expect(screen.getByRole("button", { name: "Esci" })).toBeEnabled());
});
