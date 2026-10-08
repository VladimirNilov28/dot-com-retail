import Link from "next/link";

export default function ProductNotFound() {
  return <section className="space-y-4">
    <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Product not found</h1>
    <p className="text-muted">This product may have been removed or the link may be incorrect.</p>
    <Link href="/catalog/all-products" className="store-cta">Browse all products</Link>
  </section>;
}
