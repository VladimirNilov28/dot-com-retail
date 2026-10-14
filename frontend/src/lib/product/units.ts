// Canonical storage: weight in integer grams, dimensions in integer millimetres.
// Conversion is presentation-only (the stored values are never rounded):
//   1 lb = 453.59237 g, 1 oz = 28.349523125 g, 1 in = 25.4 mm (exact).
// Metric weight: grams below 1000 g, otherwise kilograms to at most 3 decimals.
// Imperial weight: ounces (1 decimal) below 1 lb, otherwise pounds (2 decimals).
// Dimensions: millimetres as stored; centimetres (1 decimal) and inches (2 decimals).
const GRAMS_PER_POUND = 453.59237;
const GRAMS_PER_OUNCE = 28.349523125;
const MM_PER_INCH = 25.4;

function fixed(value: number, digits: number): string {
  const text = value.toFixed(digits);
  return digits === 0 ? text : text.replace(/\.?0+$/, "");
}

function valid(value: number | null | undefined): value is number {
  return typeof value === "number" && Number.isFinite(value) && value > 0;
}

export function formatWeight(grams: number | null | undefined): { metric: string; imperial: string } | null {
  // A stored 0 is data (legacy rows) and stays distinguishable from absent (null).
  if (typeof grams !== "number" || !Number.isFinite(grams) || grams < 0) return null;
  const metric = grams < 1000 ? `${fixed(grams, 0)} g` : `${fixed(grams / 1000, 3)} kg`;
  const imperial = grams < GRAMS_PER_POUND
    ? `${fixed(grams / GRAMS_PER_OUNCE, 1)} oz`
    : `${fixed(grams / GRAMS_PER_POUND, 2)} lb`;
  return { metric, imperial };
}

export interface Dimensions { lengthMm: number | null; widthMm: number | null; heightMm: number | null }

/** Absent unless all three are present; partial data is never displayed as a measurement. */
export function formatDimensions(value: Dimensions): { metric: string; imperial: string } | null {
  const parts = [value.lengthMm, value.widthMm, value.heightMm];
  if (!parts.every(valid)) return null;
  return {
    metric: `${parts.map((mm) => fixed(mm / 10, 1)).join(" × ")} cm`,
    imperial: `${parts.map((mm) => fixed(mm / MM_PER_INCH, 2)).join(" × ")} in`,
  };
}
