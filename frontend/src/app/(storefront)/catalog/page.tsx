import type { Metadata } from "next";
import { CatalogPageContent } from "@/components/catalog-page";

export const metadata: Metadata = {
  title: "Catalog",
  description: "Browse ByteCore electronics, categories and actual EUR product prices.",
};

export default async function CatalogPage({ searchParams }: PageProps<"/catalog">) {
  return <CatalogPageContent parameters={await searchParams} />;
}
