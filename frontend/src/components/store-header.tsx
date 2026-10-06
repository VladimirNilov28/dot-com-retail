import type { ReactNode } from "react";
import { PageContainer } from "@/components/page-container";
import { StoreBrand } from "@/components/store-brand";
import { StoreNavigation } from "@/components/store-navigation";

export function StoreHeader({
  quickSearch,
  cartPreview,
}: {
  quickSearch?: ReactNode;
  cartPreview?: ReactNode;
}) {
  return (
    <header className="border-b border-separator bg-background">
      <PageContainer className="store-header">
        <StoreBrand />
        <StoreNavigation quickSearch={quickSearch} cartPreview={cartPreview} />
      </PageContainer>
    </header>
  );
}
