import type { Metadata } from "next";
import Link from "next/link";
import { CartPreview } from "@/components/cart/cart-preview";
import { CartProvider } from "@/components/cart/cart-provider";
import { PageContainer } from "@/components/page-container";
import { StoreFooter } from "@/components/store-footer";
import { StoreHeader } from "@/components/store-header";

export const metadata: Metadata = {
  title: "Page not found",
  description: "The page you were looking for does not exist or has moved.",
  robots: { index: false, follow: false },
};

// Root boundary: rendered outside the (storefront) layout, so it composes the same shell itself.
export default function NotFound() {
  return (
    <CartProvider>
      <div className="flex min-h-dvh flex-col">
        <StoreHeader cartPreview={<CartPreview />} />
        <main id="main-content" tabIndex={-1} className="flex-1 py-8 sm:py-12">
          <PageContainer>
            <div className="max-w-2xl space-y-5">
              <p className="text-sm font-semibold tracking-widest text-accent uppercase">Error 404</p>
              <h1 className="text-2xl leading-tight font-semibold tracking-tight sm:text-3xl">We couldn&apos;t find that page</h1>
              <p className="text-muted">The link may be outdated or the product may no longer be listed. Try a search or browse the catalog instead.</p>
              <form action="/search" method="get" role="search" className="checkout-section not-found-search">
                <label>
                  <span>Search products</span>
                  <input name="q" type="search" autoComplete="off" maxLength={200} placeholder="e.g. laptop, monitor, headphones" />
                </label>
                <div><button type="submit" className="store-cta">Search</button></div>
              </form>
              <nav aria-label="Helpful links" className="flex flex-wrap gap-4">
                <Link href="/" className="store-link inline-flex py-2">Home</Link>
                <Link href="/catalog/all-products" className="store-link inline-flex py-2">All products</Link>
                <Link href="/search" className="store-link inline-flex py-2">Search</Link>
                <Link href="/contact" className="store-link inline-flex py-2">Contact us</Link>
              </nav>
            </div>
          </PageContainer>
        </main>
        <StoreFooter />
      </div>
    </CartProvider>
  );
}
