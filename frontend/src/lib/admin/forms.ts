/** Pure form parsing shared by admin server actions (mirrors the API's validation for early, readable errors). */

export const slugPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
export const idPattern = /^[1-9][0-9]{0,18}$/;

export function text(value: FormDataEntryValue | null, label: string, max: number, required = false): string {
  const raw = typeof value === "string" ? value.trim() : "";
  if (required && raw === "") throw new Error(`${label} is required`);
  if (raw.length > max) throw new Error(`${label} must be at most ${max} characters`);
  return raw;
}

export function slug(value: FormDataEntryValue | null, label = "Slug"): string {
  const raw = text(value, label, 255, true);
  if (!slugPattern.test(raw)) throw new Error(`${label} may only contain lowercase letters, digits and single hyphens`);
  return raw;
}

/** A positive money amount with at most two decimals, returned as the canonical decimal string. */
export function price(value: FormDataEntryValue | null, allowZero = false): string {
  const raw = typeof value === "string" ? value.trim().replace(",", ".") : "";
  if (!/^[0-9]{1,8}(\.[0-9]{1,2})?$/.test(raw)) throw new Error("Price must be a number with at most two decimals");
  if (!allowZero && Number(raw) <= 0) throw new Error("Price must be greater than zero");
  return raw;
}

export function boundedInt(value: FormDataEntryValue | null, label: string, min: number, max: number): number {
  const raw = typeof value === "string" ? value.trim() : "";
  if (!/^[0-9]{1,9}$/.test(raw)) throw new Error(`${label} must be a whole number`);
  const number = Number(raw);
  if (number < min || number > max) throw new Error(`${label} must be between ${min} and ${max}`);
  return number;
}

/** Collects attr-key-N / attr-value-N pairs into a JSON object; blank rows are skipped. */
export function attributes(form: FormData): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [name, entry] of form.entries()) {
    const match = /^attr-key-(\d{1,3})$/.exec(name);
    if (!match || typeof entry !== "string") continue;
    const key = entry.trim();
    const value = String(form.get(`attr-value-${match[1]}`) ?? "").trim();
    if (key === "" && value === "") continue;
    if (key === "") throw new Error("Attribute names are required when a value is given");
    if (key.length > 100 || value.length > 255) throw new Error("Attribute names are limited to 100 and values to 255 characters");
    if (Object.hasOwn(result, key)) throw new Error(`Attribute "${key}" is listed twice`);
    result[key] = value;
  }
  if (Object.keys(result).length > 50) throw new Error("At most 50 attributes are allowed");
  return result;
}

export function ids(form: FormData, name: string): string[] {
  const values = form.getAll(name).map(String);
  if (!values.every((value) => idPattern.test(value))) throw new Error("Invalid selection");
  return [...new Set(values)];
}

export type CategoryNode = { id: string; name: string; slug: string; parent: { id: string } | null };
export type TreeRow = CategoryNode & { depth: number };

/** Depth-first ordering for display; orphans (missing parent) are shown at the root. */
export function categoryTree(categories: CategoryNode[]): TreeRow[] {
  const known = new Set(categories.map((category) => category.id));
  const children = new Map<string | null, CategoryNode[]>();
  for (const category of categories) {
    const parent = category.parent && known.has(category.parent.id) ? category.parent.id : null;
    children.set(parent, [...(children.get(parent) ?? []), category]);
  }
  const rows: TreeRow[] = [];
  const seen = new Set<string>();
  const visit = (parent: string | null, depth: number) => {
    for (const category of [...(children.get(parent) ?? [])].sort((a, b) => a.name.localeCompare(b.name))) {
      if (seen.has(category.id)) continue;
      seen.add(category.id);
      rows.push({ ...category, depth });
      visit(category.id, depth + 1);
    }
  };
  visit(null, 0);
  return rows;
}

/** Ids a category may not be moved under: itself and its descendants. */
export function blockedParents(categories: CategoryNode[], id: string): Set<string> {
  const blocked = new Set([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const category of categories) {
      if (category.parent && blocked.has(category.parent.id) && !blocked.has(category.id)) {
        blocked.add(category.id);
        grew = true;
      }
    }
  }
  return blocked;
}

export const importLimits = { bytes: 1_048_576, rows: 500 } as const;

export function importFormat(fileName: string, requested: FormDataEntryValue | null): "CSV" | "JSON" {
  if (requested === "CSV" || requested === "JSON") return requested;
  if (/\.json$/i.test(fileName)) return "JSON";
  if (/\.csv$/i.test(fileName)) return "CSV";
  throw new Error("Choose CSV or JSON, or upload a .csv or .json file");
}
