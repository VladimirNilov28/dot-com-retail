import { LosslessNumber, parse } from "lossless-json";

declare const decimalBrand: unique symbol;
declare const uuidBrand: unique symbol;
export type Decimal = string & { readonly [decimalBrand]: true };
export type UUID = string & { readonly [uuidBrand]: true };
export type JsonValue = null | boolean | string | LosslessNumber | JsonValue[] | { [key: string]: JsonValue };

export function parseJson(source: string): unknown {
  let quoted = false;
  let escaped = false;
  let depth = 0;
  for (const character of source) {
    if (quoted) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === '"') quoted = false;
    } else if (character === '"') quoted = true;
    else if (character === "{" || character === "[") {
      if (++depth > 64) throw new TypeError("JSON nesting is too deep");
    } else if (character === "}" || character === "]") depth--;
  }
  return parse(source);
}

export function decimal(value: unknown): Decimal {
  const source = value instanceof LosslessNumber ? value.value : value;
  if (typeof source !== "string" || source.length > 256 ||
      !/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(source)) {
    throw new TypeError("Expected an exact decimal token");
  }
  return source as Decimal;
}

export function uuid(value: unknown): UUID {
  if (typeof value !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) {
    throw new TypeError("Expected a UUID");
  }
  return value as UUID;
}

export function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) ||
      ![Object.prototype, null].includes(Object.getPrototypeOf(value))) {
    throw new TypeError("Expected an object");
  }
  return value as Record<string, unknown>;
}

export function text(value: unknown): string {
  if (typeof value !== "string") throw new TypeError("Expected a string");
  return value;
}

export function list<T>(value: unknown, decode: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) throw new TypeError("Expected a list");
  return value.map(decode);
}

export function boolean(value: unknown): boolean {
  if (typeof value !== "boolean") throw new TypeError("Expected a boolean");
  return value;
}

export function integer(value: unknown): number {
  const source = value instanceof LosslessNumber ? value.value : value;
  if (typeof source === "string" && /^-?\d+$/.test(source)) {
    const exact = BigInt(source);
    if (exact >= BigInt("-2147483648") && exact <= BigInt("2147483647")) return Number(exact);
  }
  if (typeof source === "number" && Number.isInteger(source) && source >= -2147483648 && source <= 2147483647) {
    return source;
  }
  throw new TypeError("Expected a GraphQL Int");
}

export function float(value: unknown): number {
  const source = value instanceof LosslessNumber ? value.value : value;
  if (typeof source === "string" && /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(source)) {
    const parsed = Number(source);
    if (Number.isFinite(parsed)) return parsed;
  }
  if (typeof source === "number" && Number.isFinite(source)) return source;
  throw new TypeError("Expected a GraphQL Float");
}

export function json(value: unknown, depth = 0): JsonValue {
  if (depth > 64) throw new TypeError("JSON nesting is too deep");
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (value instanceof LosslessNumber) return value;
  if (Array.isArray(value)) return value.map((item) => json(item, depth + 1));
  const entries = Object.entries(object(value)).map(([key, item]) => [key, json(item, depth + 1)]);
  return Object.fromEntries(entries);
}

export function serializeJson(value: unknown, depth = 0): string {
  if (depth > 64) throw new TypeError("JSON nesting is too deep");
  if (value instanceof LosslessNumber) {
    if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value.value)) throw new TypeError("Invalid JSON number");
    return value.value;
  }
  if (value === null || typeof value === "string" || typeof value === "boolean") return JSON.stringify(value);
  if (typeof value === "number" && Number.isFinite(value)) return JSON.stringify(value);
  if (Array.isArray(value)) return `[${Array.from(value, (item) => serializeJson(item, depth + 1)).join(",")}]`;
  return `{${Object.entries(object(value))
    .filter(([, item]) => item !== undefined)
    .map(([key, item]) => `${JSON.stringify(key)}:${serializeJson(item, depth + 1)}`).join(",")}}`;
}
