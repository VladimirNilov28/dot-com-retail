import type { Metadata } from "next";
import { CatalogPageContent } from "@/components/catalog-page";

export const metadata: Metadata = {
  title: "Search",
  description: "Search ByteCore products. Actual EUR prices, no fabricated results.",
};

export default async function SearchPage({ searchParams }: PageProps<"/search">) {
  return <CatalogPageContent isSearch parameters={await searchParams} />;
}
