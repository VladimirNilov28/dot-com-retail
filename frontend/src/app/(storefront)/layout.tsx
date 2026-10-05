import { PageContainer } from "@/components/page-container";
import { StoreFooter } from "@/components/store-footer";
import { StoreHeader } from "@/components/store-header";

export default function StorefrontLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-dvh flex-col">
      <StoreHeader />
      <main id="main-content" tabIndex={-1} className="flex-1 py-10 sm:py-16">
        <PageContainer>{children}</PageContainer>
      </main>
      <StoreFooter />
    </div>
  );
}
