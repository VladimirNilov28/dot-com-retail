import type { Metadata } from "next";
import { CatalogPageContent } from "@/components/catalog-page";

export const metadata: Metadata = {
  title: "All products",
  description: "Browse every product in the ByteCore electronics catalog, from laptops to accessories. Filter by price and specs, sort by rating or price, grid or list view.",
};

export default async function AllProductsPage({ searchParams }: PageProps<"/catalog/all-products">) {
  return <CatalogPageContent parameters={await searchParams} />;
}
