import { PageContainer } from "@/components/page-container";
import { StoreFooter } from "@/components/store-footer";
import { CartPreview } from "@/components/cart/cart-preview";
import { CartProvider } from "@/components/cart/cart-provider";
import { StoreHeader } from "@/components/store-header";

export default function StorefrontLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <CartProvider>
    <div className="flex min-h-dvh flex-col">
      <StoreHeader cartPreview={<CartPreview />} />
      <main id="main-content" tabIndex={-1} className="flex-1 py-8 sm:py-12">
        <PageContainer>{children}</PageContainer>
      </main>
      <StoreFooter />
    </div>
    </CartProvider>
  );
}
