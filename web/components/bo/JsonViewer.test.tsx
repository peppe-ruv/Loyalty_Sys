import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { JsonViewer } from "./JsonViewer";

describe("JsonViewer", () => {
  it("è una regione con nome accessibile e focalizzabile da tastiera", () => {
    render(<JsonViewer value={{ a: 1 }} label="Contenuto dell'evento" />);
    const region = screen.getByRole("region", { name: "Contenuto dell'evento" });
    expect(region).toHaveAttribute("tabindex", "0");
    expect(region).toHaveTextContent('"a": 1');
  });
});
