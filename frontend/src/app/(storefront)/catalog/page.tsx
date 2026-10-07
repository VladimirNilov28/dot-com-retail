import { redirect } from "next/navigation";

// Category discovery now lives in the header's catalog megamenu
// (store-navigation.tsx), and the bounded "all products" listing lives at
// /catalog/all-products. This route renders nothing of its own: it only
// preserves bookmarked pagination/view links (including invalid values,
// which still reach the existing recovery flow at their new address) by
// forwarding the exact query string, and otherwise sends a bare request to
// the homepage rather than reviving the removed discovery composition.
export default async function CatalogPage({ searchParams }: PageProps<"/catalog">) {
  const parameters = await searchParams;
  if ("page" in parameters || "view" in parameters) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(parameters)) {
      for (const entry of Array.isArray(value) ? value : [value]) if (entry !== undefined) query.append(key, entry);
    }
    redirect(`/catalog/all-products${query.size ? `?${query}` : ""}`);
  }
  redirect("/");
}
