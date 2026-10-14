import Link from "next/link";

export default function CategoryNotFound() {
  return <section className="space-y-4">
    <h1 className="text-2xl font-semibold tracking-tight">Category not found</h1>
    <p className="text-muted">This category does not exist or is no longer available.</p>
    <Link href="/catalog" className="store-cta">Browse the catalog</Link>
  </section>;
}
