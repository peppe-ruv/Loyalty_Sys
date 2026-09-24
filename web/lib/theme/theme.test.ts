import { describe, expect, it } from "vitest";
import { AURORA, contrast, contrastChecks, normalizeTheme, themeStyle } from "./theme";

describe("tema del portale", () => {
  it("calcola il contrasto WCAG come il servizio", () => {
    expect(contrast("#000000", "#FFFFFF")).toBeCloseTo(21, 1);
    expect(contrastChecks(AURORA.colors).every((c) => c.ok)).toBe(true);
    const low = contrastChecks({ ...AURORA.colors, primary: "#2A3A55" });
    expect(low.find((c) => c.key === "primary")?.ok).toBe(false);
  });

  it("completa un tema parziale o non valido con Aurora", () => {
    const t = normalizeTheme({ programName: " ", colors: { primary: "#123456", secondary: "rosso" } as never });
    expect(t.programName).toBe("Club Aurora");
    expect(t.colors.primary).toBe("#123456");
    expect(t.colors.secondary).toBe(AURORA.colors.secondary);
    expect(normalizeTheme(null)).toBe(AURORA);
  });

  it("produce le variabili CSS dei token del portale", () => {
    expect(themeStyle(AURORA)).toMatchObject({ "--color-pt-primary": "#1FB98F", "--color-pt-bg": "#F3F7F9" });
  });
});
