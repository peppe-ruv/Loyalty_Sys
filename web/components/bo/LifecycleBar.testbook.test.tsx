import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { LifecycleBar } from "./LifecycleBar";

// Mock del server-side o API hook che innesca Can per permessi
vi.mock("@/lib/api/client", () => ({
  useLhMutation: vi.fn(() => ({ mutate: vi.fn(), isPending: false }))
}));

import React from "react";

declare global {
  interface Window {
    __MOCK_ROLE?: string;
  }
}

// Mock esplicito di Can per verificare il ruolo (per default disabilita ma mostra, o nasconde se non match)
vi.mock("./Can", () => ({
  Can: ({ capability, children, mode }: { capability: string, children: React.ReactElement<any>, mode: string }) => {
    // Simuliamo che LEGAL abbia "object.approve", MARKETING non lo abbia.
    // Lo testiamo passando data-capability o forzando disattivazione
    const isLegal = capability === "object.approve" && window.__MOCK_ROLE === "LEGAL";
    const disabled = capability === "object.approve" && window.__MOCK_ROLE === "MARKETING";

    if (disabled && mode === "disable") {
      return <div data-testid="can-wrapper">{React.cloneElement(children, { disabled: true })}</div>;
    }

    return <div data-testid="can-wrapper">{children}</div>;
  }
}));

describe("Testbook: Ciclo di Vita (LifecycleBar)", () => {
  const defaults = {
    service: "campaign" as any,
    transitionsPath: "/path",
    onChanged: vi.fn()
  };

  it("[TB-WEB-LIFE-001] DRAFT, approvalRequired=true", () => {
    render(<LifecycleBar {...defaults} status="DRAFT" approvalRequired={true} />);
    expect(screen.getByText("Invia in revisione")).toBeDefined();
    expect(screen.queryByText("Pubblica")).toBeNull();
  });

  it("[TB-WEB-LIFE-002] DRAFT, approvalRequired=false", () => {
    render(<LifecycleBar {...defaults} status="DRAFT" approvalRequired={false} />);
    expect(screen.getByText("Pubblica")).toBeDefined();
    expect(screen.queryByText("Invia in revisione")).toBeNull();
  });

  it("[TB-WEB-LIFE-003] IN_REVIEW, role=LEGAL", () => {
    // @ts-ignore
    window.__MOCK_ROLE = "LEGAL";
    render(<LifecycleBar {...defaults} status="IN_REVIEW" />);

    const approveBtn = screen.getByText("Approva") as HTMLButtonElement;
    expect(approveBtn.disabled).toBe(false);

    const rejectBtn = screen.getByText("Rifiuta…") as HTMLButtonElement;
    expect(rejectBtn.disabled).toBe(false);
  });

  it("[TB-WEB-LIFE-004] IN_REVIEW, role=MARKETING", () => {
    // @ts-ignore
    window.__MOCK_ROLE = "MARKETING";
    render(<LifecycleBar {...defaults} status="IN_REVIEW" />);

    const approveBtn = screen.getByText("Approva") as HTMLButtonElement;
    expect(approveBtn.disabled).toBe(true);

    const rejectBtn = screen.getByText("Rifiuta…") as HTMLButtonElement;
    expect(rejectBtn.disabled).toBe(true);
  });

  it("[TB-WEB-LIFE-005] LIVE", () => {
    render(<LifecycleBar {...defaults} status="LIVE" />);
    expect(screen.getByText("Metti in pausa")).toBeDefined();
    expect(screen.getByText("Termina")).toBeDefined();
  });
});
