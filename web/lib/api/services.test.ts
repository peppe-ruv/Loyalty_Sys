import { describe, it, expect, vi } from "vitest";
import { isServiceCode, serviceBaseUrl } from "./services";

describe("api/services", () => {
  it("verifica i codici di servizio", () => {
    expect(isServiceCode("wallet")).toBe(true);
    expect(isServiceCode("insight")).toBe(true);
    expect(isServiceCode("unknown")).toBe(false);
  });

  it("recupera l'URL dal process.env", () => {
    vi.stubEnv("LH_SVC_WALLET_URL", "http://prod:8084");
    expect(serviceBaseUrl("wallet")).toBe("http://prod:8084");

    vi.stubEnv("LH_SVC_WALLET_URL", "http://prod:8084/");
    expect(serviceBaseUrl("wallet")).toBe("http://prod:8084"); // strips trailing slash

    vi.unstubAllEnvs();
    expect(serviceBaseUrl("wallet")).toBe("http://localhost:8084");
  });
});
