import { notFound } from "next/navigation";
import { getCategories } from "@/lib/catalog/server";
import { categoryIndex } from "@/lib/catalog/model";

export default async function CategoryLayout({ children, params }: LayoutProps<"/catalog/[slug]">) {
  const { slug } = await params;
  const result = await getCategories();
  if (result.status === "success" && !categoryIndex(result.data.categories).bySlug.has(slug)) notFound();
  return children;
}
