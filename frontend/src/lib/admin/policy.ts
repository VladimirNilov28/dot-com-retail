/** Reads one string claim from a JWT payload without verifying it; display gating only, the API re-verifies. */
export function unverifiedClaim(token: string | undefined, name: string): string | undefined {
  const part = token?.split(".")[1];
  if (!part) return undefined;
  try {
    const value = (JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>)[name];
    return typeof value === "string" ? value : undefined;
  } catch {
    return undefined;
  }
}

export const adminRoles = new Set(["ADMIN", "CATALOG_MANAGER"]);

/** Staff role, plus a second factor unless a local-only dev opt-in is set. */
export function adminPolicy(role: string, aal: string, allowAal1: boolean): "ok" | "role" | "assurance" {
  if (!adminRoles.has(role)) return "role";
  return aal === "aal2" || allowAal1 ? "ok" : "assurance";
}

/** Parses an optional positive integer form field; "" means absent (null). */
export function optionalInt(value: FormDataEntryValue | null, max: number): number | null {
  const raw = typeof value === "string" ? value.trim() : "";
  if (raw === "") return null;
  if (!/^[0-9]{1,9}$/.test(raw)) throw new Error("Enter whole numbers only");
  const number = Number(raw);
  if (number < 1 || number > max) throw new Error(`Value must be between 1 and ${max}`);
  return number;
}

/** Moves an id one slot; returns null when the move is impossible. */
export function moveId(ids: string[], id: string, delta: -1 | 1): string[] | null {
  const from = ids.indexOf(id);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= ids.length) return null;
  const next = [...ids];
  [next[from], next[to]] = [next[to], next[from]];
  return next;
}
