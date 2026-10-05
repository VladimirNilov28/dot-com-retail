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
        <Link href="/" className="store-brand">
          ByteCore
        </Link>
        <StoreNavigation />
        {quickSearch || cartPreview ? (
          <div className="col-span-full flex min-w-0 flex-wrap items-center gap-3">
            {quickSearch}
            {cartPreview}
          </div>
        ) : null}
      </PageContainer>
    </header>
  );
}
