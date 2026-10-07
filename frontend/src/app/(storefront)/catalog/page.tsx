import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { CatalogDiscovery } from "@/components/catalog-discovery";

export const metadata: Metadata = {
  title: "Catalog",
  description: "Find a ByteCore category, or browse every product in one bounded, paginated list.",
};

export default async function CatalogPage({ searchParams }: PageProps<"/catalog">) {
  const parameters = await searchParams;
  // `/catalog` is category discovery; the bounded listing it used to render at
  // the bare root moved to /catalog/all-products. Preserve bookmarked
  // pagination/view links (including invalid values, which still reach the
  // existing recovery flow there) by forwarding the exact query string.
  if ("page" in parameters || "view" in parameters) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(parameters)) {
      for (const entry of Array.isArray(value) ? value : [value]) if (entry !== undefined) query.append(key, entry);
    }
    redirect(`/catalog/all-products${query.size ? `?${query}` : ""}`);
  }
  return <CatalogDiscovery />;
}
