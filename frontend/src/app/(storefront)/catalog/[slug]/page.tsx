import type { Metadata } from "next";
import { CatalogPageContent } from "@/components/catalog-page";
import { getCategories } from "@/lib/catalog/server";
import { categoryIndex } from "@/lib/catalog/model";
import { clampText } from "@/lib/seo";

export async function generateMetadata({ params }: PageProps<"/catalog/[slug]">): Promise<Metadata> {
  const { slug } = await params;
  const result = await getCategories();
  const category = result.status === "success" ? categoryIndex(result.data.categories).bySlug.get(slug) : undefined;
  return {
    title: category ? clampText(category.name, 48) : (result.status === "success" ? "Category not found" : "Category unavailable"),
    description: category ? clampText(`Shop ${category.name} at ByteCore. Compare products with real EUR prices, live stock and customer ratings, and filter by price and specifications to find the right fit.`, 160) : "This category is unavailable.",
  };
}

export default async function CategoryPage({ params, searchParams }: PageProps<"/catalog/[slug]">) {
  const { slug } = await params;
  return <CatalogPageContent slug={slug} parameters={await searchParams} />;
}
