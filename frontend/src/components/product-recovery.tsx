import { CatalogRetry } from "./catalog-retry";

export function ProductRecovery({ message }: { message: string }) {
  return <section className="space-y-4" aria-label="Product service error">
    <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Product unavailable</h1>
    <p className="text-muted">{message}</p>
    <CatalogRetry />
    <noscript><p><a href="" className="store-link">Reload this product</a></p></noscript>
  </section>;
}
