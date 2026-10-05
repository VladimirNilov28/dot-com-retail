import { FoundationDemo } from "@/components/foundation-demo";
import { PageContainer } from "@/components/page-container";

export default function Home() {
  return (
    <main id="main-content" tabIndex={-1} className="py-12 sm:py-20">
      <PageContainer className="space-y-12">
        <header className="max-w-2xl space-y-4">
          <p className="text-sm font-semibold tracking-widest text-accent uppercase">
            ByteCore / Foundation
          </p>
          <h1 className="text-4xl leading-tight font-semibold tracking-tight sm:text-5xl">
            A small start. A consistent foundation.
          </h1>
          <p className="text-lg text-muted">
            Real HeroUI components, a shared visual language, and a dark theme
            from the first render. This is a UI playground, not a storefront.
          </p>
        </header>

        <section aria-labelledby="playground-heading" className="space-y-5">
          <h2 id="playground-heading" className="text-2xl font-semibold">
            Component playground
          </h2>
          <FoundationDemo />
        </section>

        <footer className="border-t border-separator pt-6 text-sm text-muted">
          Next.js App Router + React Compiler + HeroUI v3. No backend connection
          or environment configuration is required for this demonstration.
        </footer>
      </PageContainer>
    </main>
  );
}
