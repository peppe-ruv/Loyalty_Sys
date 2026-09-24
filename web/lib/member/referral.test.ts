import { describe, expect, it } from "vitest";
import { completedThisEdition, formatRate, inviteLink, inviteeStatusLabel, referralFunnel } from "./referral";

describe("referral", () => {
  it("costruisce il link di invito assoluto", () => {
    expect(inviteLink("https://demo.example.org/", "/portal/join?ref=AB23CD45")).toBe("https://demo.example.org/portal/join?ref=AB23CD45");
  });

  it("conta solo i completamenti dell'anno corrente", () => {
    const now = new Date("2026-09-24T10:00:00Z");
    const invited = [
      { nickname: "a", status: "COMPLETED" as const, registeredAt: null, completedAt: "2026-03-01T10:00:00Z" },
      { nickname: "b", status: "COMPLETED" as const, registeredAt: null, completedAt: "2025-12-01T10:00:00Z" },
      { nickname: "c", status: "PENDING" as const, registeredAt: null, completedAt: null },
    ];
    expect(completedThisEdition(invited, now)).toBe(1);
  });

  it("descrive stato, tasso e imbuto", () => {
    expect(inviteeStatusLabel("PENDING")).toBe("iscritto");
    expect(inviteeStatusLabel("COMPLETED")).toBe("premio ottenuto");
    expect(formatRate(0.333)).toBe("33%");
    expect(referralFunnel({ invited: 4, completed: 1 }).map((s) => s.value)).toEqual([4, 4, 1, 1]);
  });
});
