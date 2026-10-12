import { describe, expect, test } from "bun:test";
import { formatDimensions, formatWeight } from "./units";

describe("canonical unit presentation", () => {
  test("weight converts from grams with documented rounding", () => {
    expect(formatWeight(40)).toEqual({ metric: "40 g", imperial: "1.4 oz" });
    expect(formatWeight(999)).toEqual({ metric: "999 g", imperial: "2.2 lb" });
    expect(formatWeight(1000)).toEqual({ metric: "1 kg", imperial: "2.2 lb" });
    expect(formatWeight(1234)).toEqual({ metric: "1.234 kg", imperial: "2.72 lb" });
    expect(formatWeight(453)).toEqual({ metric: "453 g", imperial: "16 oz" });
  });
  test("absent weight is not shown as zero, while a stored zero stays distinct", () => {
    expect(formatWeight(null)).toBeNull();
    expect(formatWeight(undefined)).toBeNull();
    expect(formatWeight(-1)).toBeNull();
    expect(formatWeight(0)).toEqual({ metric: "0 g", imperial: "0 oz" });
  });
  test("dimensions convert from millimetres and require all three values", () => {
    expect(formatDimensions({ lengthMm: 254, widthMm: 100, heightMm: 25 })).toEqual({
      metric: "25.4 × 10 × 2.5 cm", imperial: "10 × 3.94 × 0.98 in",
    });
    expect(formatDimensions({ lengthMm: 254, widthMm: null, heightMm: 25 })).toBeNull();
    expect(formatDimensions({ lengthMm: null, widthMm: null, heightMm: null })).toBeNull();
  });
});
