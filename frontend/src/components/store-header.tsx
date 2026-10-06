import Link from "next/link";
import type { ReactNode } from "react";
import { PageContainer } from "@/components/page-container";
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
        <div className="store-branding">
          <Link href="/" className="store-brand">ByteCore</Link>
          <p className="text-xs text-muted">Electronics, clearly connected.</p>
        </div>
        <StoreNavigation quickSearch={quickSearch} cartPreview={cartPreview} />
      </PageContainer>
    </header>
  );
}
