import type { ReactNode } from "react";
import { PageContainer } from "@/components/page-container";
import { StoreBrand } from "@/components/store-brand";
import { StoreNavigation } from "@/components/store-navigation";
import { getCategories } from "@/lib/catalog/server";

export async function StoreHeader({
  cartPreview,
}: {
  cartPreview?: ReactNode;
}) {
  const result = await getCategories();
  return (
    <header className="border-b border-separator bg-background">
      <PageContainer className="store-header">
        <StoreBrand />
        <StoreNavigation cartPreview={cartPreview}
          categories={result.status === "success" ? result.data.categories : []}
          categoryError={result.status !== "success"} />
      </PageContainer>
    </header>
  );
}
