// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from "vitest";
import DemoLayout from "./layout";

// V11 (ADR-051): le schermate Demo (/backoffice/demo/**) non esistono in enterprise: 404, anche digitando l'indirizzo.

const getViewer = vi.fn();
vi.mock("@/lib/auth/viewer", () => ({ getViewer: () => getViewer() }));
vi.mock("next/navigation", () => ({
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));

beforeEach(() => getViewer.mockReset());

describe("layout /backoffice/demo", () => {
  it("demo: mostra la pagina", async () => {
    getViewer.mockResolvedValue({ mode: "demo" });
    const out = await DemoLayout({ children: "contenuto" });
    expect((out as { props: { children: string } }).props.children).toBe("contenuto");
  });

  it.each([null, { username: "marta.admin", role: "ADMIN", kind: "operator" }])("enterprise (sessione %j): 404", async (user) => {
    getViewer.mockResolvedValue({ mode: "enterprise", user });
    await expect(DemoLayout({ children: "contenuto" })).rejects.toThrow("NEXT_NOT_FOUND");
  });
});
