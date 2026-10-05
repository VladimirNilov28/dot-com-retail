import Link from "next/link";
import { PageContainer } from "@/components/page-container";

export function StoreFooter() {
  return (
    <footer className="border-t border-separator py-6 text-sm text-muted">
      <PageContainer className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <p>ByteCore storefront in progress.</p>
        <Link href="/" className="store-link">
          Back to home
        </Link>
      </PageContainer>
    </footer>
  );
}
