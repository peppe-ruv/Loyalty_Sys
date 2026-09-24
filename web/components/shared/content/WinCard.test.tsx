import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { WinCard } from "./WinCard";

describe("WinCard", () => {
  it("usa la card configurata per il premio, con il pulsante verso la sua pagina", () => {
    render(
      <WinCard
        content={{
          code: "WIN-POINTS-100", kind: "CARD", placement: "WIN", title: "100 punti per te!", body: "Li trovi nel saldo.",
          imageUrl: null, ctaLabel: "La mia attività", ctaTarget: "/portal/activity", linkType: "PRIZE", linkCode: "PTS-100",
          style: { tone: "PRIMARY" },
        }}
        prizeLabel="100 punti"
        followUp="I punti stanno arrivando…"
      />,
    );
    expect(screen.getByText("100 punti per te!")).toBeTruthy();
    expect(screen.getByText("Li trovi nel saldo.")).toBeTruthy();
    expect(screen.getByRole("link", { name: /La mia attività/ }).getAttribute("href")).toBe("/portal/activity");
  });

  it("senza card configurata ripiega sul riquadro generico", () => {
    render(<WinCard content={null} prizeLabel="Powerbank solare" followUp="Ti contatteremo per la consegna." />);
    expect(screen.getByText("Powerbank solare")).toBeTruthy();
    expect(screen.getByText("Ti contatteremo per la consegna.")).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
  });
});
