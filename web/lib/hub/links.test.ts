import { describe, expect, it } from "vitest";
import { NAV } from "@/lib/nav";
import { KAFKA_RESTART_DOCS_URL, RECOMMENDED_PATH } from "./links";

describe("hub/links (HUB-01)", () => {
  it("il percorso consigliato ha 5 passi nell'ordine della spec", () => {
    expect(RECOMMENDED_PATH.map((s) => s.screen)).toEqual(["BO-29", "BO-24", "PT-01", "BO-06", "BO-30"]);
  });

  it("i passi del backoffice puntano alle rotte del menu", () => {
    const hrefById = new Map(NAV.flatMap((g) => g.items).map((i) => [i.id, i.href]));
    for (const step of RECOMMENDED_PATH.filter((s) => s.screen !== "BO-06" && s.screen.startsWith("BO-"))) {
      expect(step.href).toBe(hrefById.get(step.screen));
    }
    expect(RECOMMENDED_PATH.find((s) => s.screen === "BO-06")?.href).toBe("/backoffice/campaigns/new");
    expect(RECOMMENDED_PATH.find((s) => s.screen === "PT-01")?.href).toBe("/portal");
  });

  it("il riquadro Kafka rimanda a docs/11 §3", () => {
    expect(KAFKA_RESTART_DOCS_URL).toMatch(/docs\/11-DEPLOY-COSTO-ZERO\.md#3-kafka-su-aiven/);
  });
});
