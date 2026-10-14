/** Shortens text at a word boundary so it fits search-result snippets (descriptions ~160, titles < 60 with suffix). */
export function clampText(value: string, max: number): string {
  const text = value.replace(/\s+/g, " ").trim();
  if (text.length <= max) return text;
  const cut = text.slice(0, max - 1);
  const space = cut.lastIndexOf(" ");
  return `${(space > max * 0.6 ? cut.slice(0, space) : cut).replace(/[\s,.;:–-]+$/, "")}…`;
}

/** Product meta description: the product's own text, padded with honest store context when short. */
export function productDescription(name: string, description: string | null | undefined): string {
  const own = description?.replace(/\s+/g, " ").trim() ?? "";
  if (own.length >= 120) return clampText(own, 160);
  const context = `Buy ${name} at ByteCore. Compare variants, real EUR prices, stock and ratings, with metric and imperial specs.`;
  return clampText(own ? `${own} ${context}` : context, 160);
}
