import { describe, it, expect } from "vitest";
import { isRfc9457, mapRfc9457ToFormErrors } from "./status";

describe("api/status", () => {
  it("riconosce gli errori RFC 9457", () => {
    expect(isRfc9457({ type: "test", status: 400 })).toBe(true);
    expect(isRfc9457(null)).toBe(false);
    expect(isRfc9457("error")).toBe(false);
  });

  it("mappa gli errori RFC 9457 nei campi", () => {
    const error = {
      type: "about:blank",
      status: 400,
      detail: "Invalid input",
      violations: [
        { field: "name", message: "Required" },
        { field: "email", message: "Invalid email" }
      ]
    };

    // Simula una funzione setError di react-hook-form
    const errorsSet: Record<string, any> = {};
    const setError = (field: string, error: any) => {
      errorsSet[field] = error;
    };

    mapRfc9457ToFormErrors(error, setError as any);

    expect(errorsSet["name"]).toEqual({ type: "server", message: "Required" });
    expect(errorsSet["email"]).toEqual({ type: "server", message: "Invalid email" });
  });

  it("non fa nulla se l'errore non ha violazioni", () => {
    const error = {
      type: "about:blank",
      status: 400,
      detail: "Invalid input"
    };

    const errorsSet: Record<string, any> = {};
    const setError = (field: string, error: any) => {
      errorsSet[field] = error;
    };

    mapRfc9457ToFormErrors(error, setError as any);

    expect(Object.keys(errorsSet).length).toBe(0);
  });
});
