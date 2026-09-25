import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import type { Role } from "@/lib/persona/personas";
import { NavLinks } from "./NavLinks";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §NAV (voci per ruolo): docs/08 §2 "Tutte le personas leggono tutto" → la sidebar è la stessa per
// ogni ruolo: 28 voci (docs/08 §1, M1–M7 realizzate).

vi.mock("next/navigation", () => ({ usePathname: () => "/backoffice/members" }));

beforeEach(() => {
  // I contatori interrogano i servizi: risposta mai risolta (restano senza numero).
  vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(() => {})));
});
afterEach(() => vi.unstubAllGlobals());

it.each(
  rows(
    (["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"] as Role[]).map((role, i) => ({
      id: `TB-WEB-NAV-0${48 + i}`,
      desc: `sidebar per ${role} → 28 voci, tutte le schermate in lettura`,
      role,
    })),
  ),
)("[%s] %s", (_id, _desc, { role }) => {
  renderWithProviders(<NavLinks />, role);
  expect(screen.getAllByRole("link")).toHaveLength(28);
  expect(screen.getByRole("link", { name: /Approvazioni/ })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /Console demo/ })).toBeInTheDocument();
});
