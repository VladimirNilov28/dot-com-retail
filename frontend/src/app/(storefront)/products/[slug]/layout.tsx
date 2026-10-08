import { notFound } from "next/navigation";
import { getProduct } from "@/lib/product/server";

export default async function ProductLayout({ children, params }: LayoutProps<"/products/[slug]">) {
  const { slug } = await params;
  const result = await getProduct(slug);
  if (result.status === "success" && result.data.product === null) notFound();
  return children;
}
