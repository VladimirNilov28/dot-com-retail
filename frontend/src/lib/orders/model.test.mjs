import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { buildSchema, parse, validate } from "graphql";
import * as documents from "./documents";
import {
  isOrderReference, noteText, orderVariables, pageCount, parseOrderSearch, safeTrackingUrl, searchHref, trackingInput,
} from "./model";

describe("order list search params", () => {
  test("keeps valid filters and converts the inclusive end date to an exclusive bound", () => {
    const search = parseOrderSearch({ status: "PROCESSING", from: "2025-01-01", to: "2025-01-31", page: "3" });
    expect(search.error).toBeNull();
    expect(orderVariables(search)).toEqual({
      filter: { status: "PROCESSING", from: "2025-01-01T00:00:00.000Z", to: "2025-02-01T00:00:00.000Z" }, page: 2, size: 20,
    });
    expect(searchHref("/account/orders", search, 4)).toBe("/account/orders?status=PROCESSING&from=2025-01-01&to=2025-01-31&page=4");
  });
  test("drops junk instead of forwarding it", () => {
    const search = parseOrderSearch({ status: "HACKED", from: "2025-02-30", to: ["2025-01-01", "x"], page: "-1", q: "a@b.c" });
    expect(search).toMatchObject({ status: null, from: null, to: null, page: 1, query: null });
    expect(search.error).toContain("Unknown status");
    expect(orderVariables(search)).toEqual({ filter: null, page: 0, size: 20 });
  });
  test("rejects reversed ranges and bounds the page", () => {
    expect(parseOrderSearch({ from: "2025-02-01", to: "2025-01-01" })).toMatchObject({ from: null, to: null });
    expect(parseOrderSearch({ page: "99999" }).page).toBe(10_000);
    expect(parseOrderSearch({ page: "1e3" }).page).toBe(1);
  });
  test("only staff searches carry a free-text query", () => {
    expect(parseOrderSearch({ q: " shopper@example.com " }, { staff: true }).query).toBe("shopper@example.com");
    expect(parseOrderSearch({ q: "x".repeat(400) }, { staff: true }).query).toHaveLength(254);
  });
  test("page count never drops below one", () => {
    expect(pageCount(0, 20)).toBe(1);
    expect(pageCount(41, 20)).toBe(3);
  });
});

describe("order input validation", () => {
  test("tracking links must be credential-free http(s)", () => {
    expect(safeTrackingUrl("https://track.example/p?id=1")).toBe("https://track.example/p?id=1");
    for (const url of ["javascript:alert(1)", "data:text/html,x", "https://user:pw@x.example/", "/relative", "ftp://x.example"]) {
      expect(safeTrackingUrl(url)).toBeNull();
    }
  });
  test("tracking form mirrors the API rules", () => {
    const form = (values) => { const f = new FormData(); for (const [k, v] of Object.entries(values)) f.set(k, v); return f; };
    expect(trackingInput(form({ carrier: "DHL Express", trackingNumber: "JD0146-0001", trackingUrl: "" })))
      .toEqual({ carrier: "DHL Express", trackingNumber: "JD0146-0001", trackingUrl: null });
    expect(() => trackingInput(form({ carrier: "<b>", trackingNumber: "JD01" }))).toThrow();
    expect(() => trackingInput(form({ carrier: "DHL", trackingNumber: "J D" }))).toThrow();
    expect(() => trackingInput(form({ carrier: "DHL", trackingNumber: "JD0146", trackingUrl: "javascript:alert(1)" }))).toThrow();
  });
  test("notes are 3..1000 characters and optional only when allowed", () => {
    expect(noteText("", "Note", false)).toBeNull();
    expect(() => noteText("", "Reason", true)).toThrow("Reason is required.");
    expect(() => noteText("ab", "Reason", true)).toThrow();
    expect(noteText("Item\u0000 arrived broken", "Reason", true)).toBe("Item arrived broken");
  });
  test("order references are UUIDs", () => {
    expect(isOrderReference("11111111-1111-4111-8111-111111111111")).toBe(true);
    expect(isOrderReference("../admin")).toBe(false);
  });
});

test("every order document validates against the composed supergraph", () => {
  const schema = buildSchema(readFileSync(new URL("../../../../infrastructure/hive/supergraph.graphql", import.meta.url), "utf8"));
  const sources = Object.entries(documents).filter(([, value]) => typeof value === "string" && /^(query|mutation) /.test(value));
  expect(sources.length).toBe(11);
  for (const [name, source] of sources) {
    expect({ name, errors: validate(schema, parse(source)).map((error) => error.message) }).toEqual({ name, errors: [] });
  }
});
