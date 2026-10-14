import type { Metadata } from "next";
import Link from "next/link";
import { AdminDenied } from "@/components/admin-denied";
import { AdminStatus } from "@/components/admin-status";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";

export const metadata: Metadata = {
  title: "Admin · Products",
  description: "Staff product catalogue: create new products, edit names, slugs, descriptions, brands and categories, and manage variants, prices, stock and product media.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

type Item = { id: string; name: string; slug: string; images: { url: string }[]; variants: { isActive: boolean }[] };

export default async function AdminProducts({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} />;
  let items: Item[] = [];
  let failed = false;
  try {
    // The staff list includes products without active variants, which search hides.
    const data = await adminGraphql<{ products: Item[] }>(access.session.accessToken,
      `query { products { id name slug images { url } variants { isActive } } }`);
    items = [...data.products].sort((a, b) => a.name.localeCompare(b.name));
  } catch {
    failed = true;
  }
  return (
    <div className="account-main">
      <h1 className="text-2xl font-semibold tracking-tight">Product management</h1>
      <AdminStatus params={params} />
      <p><Link prefetch={false} className="store-cta" href="/admin/products/new">New product</Link></p>
      {failed ? <p role="alert" className="text-muted">Products could not be loaded. Retry shortly.</p> : null}
      <ul className="account-card" aria-label="Products">
        {items.map((item) => {
          const active = item.variants.filter((variant) => variant.isActive).length;
          return (
            <li key={item.id} className="account-row">
              <Link prefetch={false} className="store-link" href={`/admin/products/${item.slug}`}>{item.name}</Link>
              <span className="account-badge" data-tone={active ? "ok" : "off"}>{active}/{item.variants.length} variants active</span>
              <span className="account-badge" data-tone={item.images.length ? "ok" : "off"}>{item.images.length} image{item.images.length === 1 ? "" : "s"}</span>
            </li>
          );
        })}
        {!failed && items.length === 0 ? <li className="text-muted">No products yet.</li> : null}
      </ul>
    </div>
  );
}
