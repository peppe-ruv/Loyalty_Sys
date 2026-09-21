import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { Button } from "./button";

describe("ui/Button", () => {
  it("rende il testo e rispetta disabled", () => {
    render(<Button disabled>Accendi</Button>);
    const btn = screen.getByRole("button", { name: "Accendi" });
    expect(btn).toBeInTheDocument();
    expect(btn).toBeDisabled();
  });
});
