import { LosslessNumber } from "lossless-json";
import { attributeLabel, comparePrice } from "@/lib/catalog/model";
import type { ProductDetailQuery } from "@/lib/graphql/generated";
import type { JsonValue } from "@/lib/graphql/scalars";

export type Product = NonNullable<ProductDetailQuery["product"]>;
export type Variant = Product["variants"][number];
export type Parameters = Record<string, string | string[] | undefined>;

export function variantId(value: unknown): value is string {
  return typeof value === "string" && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= BigInt("9223372036854775807");
}

export function selectVariant(product: Pick<Product, "variants">, parameters: Parameters) {
  const active = product.variants.filter((variant) => variant.isActive);
  const initial = active.reduce<Variant | null>((current, candidate) => {
    if (!current) return candidate;
    const price = comparePrice(candidate.price, current.price);
    return price < 0 || (price === 0 && BigInt(candidate.id) < BigInt(current.id)) ? candidate : current;
  }, null);
  const requested = parameters.variant;
  if (requested === undefined) return { selected: initial, recovered: false };
  const selected = variantId(requested) ? product.variants.find(({ id }) => id === requested) : undefined;
  return selected ? { selected, recovered: false } : { selected: initial, recovered: true };
}

export function variantHref(slug: string, id: string | null, parameters: Parameters = {}) {
  if (id !== null && !variantId(id)) throw new TypeError("Invalid variant identifier");
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(parameters)) {
    if (key === "variant" || value === undefined) continue;
    for (const item of Array.isArray(value) ? value : [value]) query.append(key, item);
  }
  if (id !== null) query.set("variant", id);
  const route = `/products/${encodeURIComponent(slug)}`;
  return query.size ? `${route}?${query}` : route;
}

function attributeValue(value: JsonValue): string {
  if (value === null || value === "") return "Not specified";
  if (typeof value === "string") return value;
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (value instanceof LosslessNumber) return value.value;
  if (Array.isArray(value)) return value.length ? value.map(attributeValue).join(", ") : "Not specified";
  const entries = Object.entries(value);
  return entries.length ? entries.map(([key, item]) => `${attributeLabel(key)}: ${attributeValue(item)}`).join("; ") : "Not specified";
}

function attributes(variant: Variant): [string, string][] {
  const value = variant.attributes;
  if (value === null || typeof value !== "object" || Array.isArray(value) || value instanceof LosslessNumber) {
    return [["Specifications", attributeValue(value)]];
  }
  return Object.entries(value).sort(([a], [b]) => a.localeCompare(b, "en"))
    .map(([key, item]) => [attributeLabel(key), attributeValue(item)]);
}

export function variantSpecifications(variant: Variant): [string, string][] {
  return [
    ["SKU", variant.sku],
    ...attributes(variant),
    ...(variant.weightGrams === null ? [] : [["Weight (grams)", String(variant.weightGrams)] as [string, string]]),
    ...(variant.barcode ? [["Barcode", variant.barcode] as [string, string]] : []),
  ];
}

export function variantOptionLabels(variants: Variant[]): string[] {
  const rows = variants.map(attributes);
  const labels = new Set(rows.flatMap((row) => row.map(([label]) => label)));
  return [...labels].filter((label) => new Set(rows.map((row) => row.find(([key]) => key === label)?.[1])).size > 1);
}

export function variantLabel(variant: Variant, optionLabels?: readonly string[]) {
  const options = attributes(variant).filter(([label]) => optionLabels === undefined || optionLabels.includes(label))
    .map(([key, value]) => `${key}: ${value}`);
  return options.length ? `${options.join(" · ")} — ${variant.sku}` : variant.sku;
}
