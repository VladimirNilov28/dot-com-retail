import type { Metadata } from "next";
import Link from "next/link";
import { AdminDenied } from "@/components/admin-denied";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";

export const metadata: Metadata = { title: "Admin · Products", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

type Item = { id: string; name: string; slug: string; images: { url: string }[] };

export default async function AdminProducts() {
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} />;
  let items: Item[] = [];
  let failed = false;
  try {
    const data = await adminGraphql<{ searchProducts: { items: Item[] } }>(access.session.accessToken,
      `query { searchProducts(input: { size: 50 }) { items { id name slug images { url } } } }`);
    items = data.searchProducts.items;
  } catch {
    failed = true;
  }
  return (
    <div className="account-main">
      <h1 className="text-2xl font-semibold tracking-tight">Product management</h1>
      {failed ? <p role="alert" className="text-muted">Products could not be loaded. Retry shortly.</p> : null}
      <ul className="account-card" aria-label="Products">
        {items.map((item) => (
          <li key={item.id} className="account-row">
            <Link prefetch={false} className="store-link" href={`/admin/products/${item.slug}`}>{item.name}</Link>
            <span className="account-badge" data-tone={item.images.length ? "ok" : "off"}>{item.images.length} image{item.images.length === 1 ? "" : "s"}</span>
          </li>
        ))}
        {!failed && items.length === 0 ? <li className="text-muted">No products yet.</li> : null}
      </ul>
    </div>
  );
}
