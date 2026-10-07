import type { Metadata } from "next";
import { CatalogPageContent } from "@/components/catalog-page";

export const metadata: Metadata = {
  title: "All products",
  description: "Every ByteCore product in one bounded, paginated list. Actual EUR prices.",
};

export default async function AllProductsPage({ searchParams }: PageProps<"/catalog/all-products">) {
  return <CatalogPageContent parameters={await searchParams} />;
}
