import Link from "next/link";
import { PageContainer } from "@/components/page-container";

export function StoreFooter() {
  return (
    <footer className="border-t border-separator py-6 text-sm text-muted">
      <PageContainer className="store-footer">
        <div>
          <p className="text-lg font-semibold text-foreground">ByteCore</p>
          <p>Electronics, clearly connected.</p>
          <p className="mt-3">Storefront in progress. Shopping features are not available yet.</p>
        </div>
        <nav aria-label="Footer catalog" className="grid gap-2">
          <p className="font-semibold text-foreground">Discover</p>
          <Link href="/catalog" className="store-link">Catalog</Link>
          <Link href="/search" className="store-link">Search</Link>
          <Link href="/" className="store-link">Back to home</Link>
        </nav>
        <nav aria-label="Footer account" className="grid gap-2">
          <p className="font-semibold text-foreground">Your shopping</p>
          <Link href="/account" className="store-link">Account</Link>
          <Link href="/cart" className="store-link">Cart</Link>
        </nav>
      </PageContainer>
    </footer>
  );
}
