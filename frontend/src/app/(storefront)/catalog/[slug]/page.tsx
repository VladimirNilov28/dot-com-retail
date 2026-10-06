import type { Metadata } from "next";
import { CatalogPageContent } from "@/components/catalog-page";
import { getCategories } from "@/lib/catalog/server";
import { categoryIndex } from "@/lib/catalog/model";

export async function generateMetadata({ params }: PageProps<"/catalog/[slug]">): Promise<Metadata> {
  const { slug } = await params;
  const result = await getCategories();
  const category = result.status === "success" ? categoryIndex(result.data.categories).bySlug.get(slug) : undefined;
  return {
    title: category?.name ?? (result.status === "success" ? "Category not found" : "Category unavailable"),
    description: category ? `Browse products assigned to ${category.name} at ByteCore. View actual EUR prices.` : "This category is unavailable.",
  };
}

export default async function CategoryPage({ params, searchParams }: PageProps<"/catalog/[slug]">) {
  const { slug } = await params;
  return <CatalogPageContent slug={slug} parameters={await searchParams} />;
}
