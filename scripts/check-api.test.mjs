import test from "node:test";
import assert from "node:assert";
import { checkBreakingChanges } from "./check-api.mjs";

test("check-api detecting breaking changes", () => {
    const base = {
        paths: {
            "/test": {
                get: {
                    responses: {
                        "200": {
                            content: {
                                "application/json": {
                                    schema: {
                                        properties: {
                                            id: { type: "string" },
                                            name: { type: "string" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        components: {
            schemas: {
                User: {
                    type: "object",
                    required: ["id"],
                    properties: {
                        id: { type: "string" },
                        status: { type: "string", enum: ["ACTIVE", "INACTIVE"] }
                    }
                }
            }
        }
    };

    const current = {
        paths: {
            "/test": {
                get: {
                    responses: {
                        "200": {
                            content: {
                                "application/json": {
                                    schema: {
                                        properties: {
                                            id: { type: "string" }
                                            // name removed
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        components: {
            schemas: {
                User: {
                    type: "object",
                    required: ["id", "name"], // name added to required
                    properties: {
                        id: { type: "integer" }, // type changed
                        status: { type: "string", enum: ["ACTIVE"] } // INACTIVE removed
                    }
                }
            }
        }
    };

    const errors = checkBreakingChanges(base, current);

    assert.strictEqual(errors.length, 4);
    assert.ok(errors.find(e => e.includes("Removed response property") && e.includes("name")));
    assert.ok(errors.find(e => e.includes("Added required request field") && e.includes("name")));
    assert.ok(errors.find(e => e.includes("Type changed") && e.includes("id")));
    assert.ok(errors.find(e => e.includes("Narrowed enum") && e.includes("INACTIVE")));
});

test("check-api detecting removed paths and schemas", () => {
    const base = {
        paths: {
            "/a": {},
            "/b": {}
        },
        components: {
            schemas: {
                ModelA: { properties: { a: { type: "string" } } },
                ModelB: { properties: { b: { type: "string" } } }
            }
        }
    };

    const current = {
        paths: {
            "/a": {} // /b removed
        },
        components: {
            schemas: {
                ModelA: { properties: {} } // ModelB removed, property a removed
            }
        }
    };

    const errors = checkBreakingChanges(base, current);
    assert.strictEqual(errors.length, 3);
    assert.ok(errors.find(e => e.includes("Removed path or operation: paths./b")));
    assert.ok(errors.find(e => e.includes("Removed schema or property: components.schemas.ModelB")));
    assert.ok(errors.find(e => e.includes("Removed schema or property: components.schemas.ModelA.properties.a")));
});

test("check-api type change", () => {
    const base = { type: "string" };
    const current = { type: "integer" };
    const errors = checkBreakingChanges(base, current);
    assert.strictEqual(errors.length, 1);
    assert.ok(errors[0].includes("Type changed"));
});

test("check-api no breaking changes", () => {
    const base = { type: "string" };
    const current = { type: "string", newField: "allowed" };
    const errors = checkBreakingChanges(base, current);
    assert.strictEqual(errors.length, 0);
});
