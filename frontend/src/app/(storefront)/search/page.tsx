import type { Metadata } from "next";
import { CatalogPageContent } from "@/components/catalog-page";

export const metadata: Metadata = {
  title: "Search products",
  description: "Search the ByteCore electronics catalog by name or keyword, then sort and filter results by price, category and specs. Real EUR prices and live stock levels.",
};

export default async function SearchPage({ searchParams }: PageProps<"/search">) {
  return <CatalogPageContent isSearch parameters={await searchParams} />;
}
