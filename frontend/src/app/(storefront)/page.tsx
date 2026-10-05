import Link from "next/link";
import { FoundationDemo } from "@/components/foundation-demo";

export default function Home() {
  return (
    <div className="space-y-12">
      <header className="max-w-2xl space-y-4">
        <p className="text-sm font-semibold tracking-widest text-accent uppercase">
          ByteCore
        </p>
        <h1 className="text-4xl leading-tight font-semibold tracking-tight sm:text-5xl">
          A small start. A consistent foundation.
        </h1>
        <p className="text-lg text-muted">
          Our storefront is taking shape. Catalog, search, account, and cart
          features are not available yet.
        </p>
        <Link href="/catalog" className="store-link inline-flex py-2">
          Visit the catalog
        </Link>
      </header>

      <section aria-labelledby="playground-heading" className="space-y-5">
        <h2 id="playground-heading" className="text-2xl font-semibold">
          Component playground
        </h2>
        <FoundationDemo />
      </section>
    </div>
  );
}
