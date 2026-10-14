import type { Metadata } from "next";
import Link from "next/link";
import { ChevronRight, ImageOff, PackageSearch, Plus, Search, Upload } from "lucide-react";
import { AdminDenied } from "@/components/admin-denied";
import { AdminEmpty, AdminPageHeader } from "@/components/admin-page-header";
import { AdminLoadError, AdminStatus } from "@/components/admin-status";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { variantUrl } from "@/lib/media/variants";

export const metadata: Metadata = {
  title: "Admin · Products",
  description: "Staff product catalogue: create new products, edit names, slugs, descriptions, brands and categories, and manage variants, prices, stock and product media.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

type Item = { id: string; name: string; slug: string; images: { url: string; altText: string }[]; variants: { isActive: boolean }[] };
const filters = [
  { value: "", label: "All" },
  { value: "attention", label: "Needs attention" },
] as const;

export default async function AdminProducts({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} />;
  const q = typeof params.q === "string" ? params.q.trim().slice(0, 100) : "";
  const show = params.show === "attention" ? "attention" : "";
  let items: Item[] = [];
  let failed = false;
  try {
    // The staff list includes products without active variants, which search hides.
    const data = await adminGraphql<{ products: Item[] }>(access.session.accessToken,
      `query { products { id name slug images { url altText } variants { isActive } } }`);
    items = [...data.products].sort((a, b) => a.name.localeCompare(b.name));
  } catch {
    failed = true;
  }
  const needsAttention = (item: Item) => item.images.length === 0 || !item.variants.some((variant) => variant.isActive);
  const term = q.toLowerCase();
  const shown = items.filter((item) => (!term || item.name.toLowerCase().includes(term) || item.slug.includes(term)) &&
    (!show || needsAttention(item)));
  const href = (value: string) => {
    const query = new URLSearchParams();
    if (q) query.set("q", q);
    if (value) query.set("show", value);
    const text = query.toString();
    return text ? `/admin/products?${text}` : "/admin/products";
  };
  return (
    <div className="account-main">
      <AdminPageHeader title="Products" description="Create products, then manage variants, prices, media and stock on each product page."
        actions={<>
          <Link prefetch={false} className="store-cta" href="/admin/import"><Upload size={16} aria-hidden="true" />Import</Link>
          <Link prefetch={false} className="store-cta" data-variant="primary" href="/admin/products/new"><Plus size={16} aria-hidden="true" />New product</Link>
        </>} />
      <AdminStatus params={params} />
      {failed ? <AdminLoadError>Products could not be loaded. Retry shortly.</AdminLoadError> : (
        <dl className="admin-stats">
          <div className="admin-stat"><dt>Products</dt><dd>{items.length}</dd></div>
          <div className="admin-stat"><dt>Without an active variant</dt><dd>{items.filter((item) => !item.variants.some((v) => v.isActive)).length}</dd></div>
          <div className="admin-stat"><dt>Without images</dt><dd>{items.filter((item) => item.images.length === 0).length}</dd></div>
        </dl>
      )}
      <form method="get" action="/admin/products" className="admin-toolbar" role="search">
        <label className="auth-field admin-search"><span className="text-sm">Search by name or slug</span>
          <input className="auth-input" type="search" name="q" defaultValue={q} maxLength={100} placeholder="e.g. ryzen" /></label>
        {show ? <input type="hidden" name="show" value={show} /> : null}
        <button className="store-cta" type="submit"><Search size={16} aria-hidden="true" />Search</button>
        <nav aria-label="Product filter" className="admin-segmented">
          {filters.map((filter) => (
            <Link key={filter.label} prefetch={false} href={href(filter.value)} aria-current={show === filter.value ? "page" : undefined}>{filter.label}</Link>
          ))}
        </nav>
      </form>
      {!failed && shown.length === 0 ? (
        <div className="admin-panel">
          <AdminEmpty icon={<PackageSearch size={22} />} title={items.length ? "No products match" : "No products yet"}>
            {items.length ? <Link prefetch={false} className="store-link" href="/admin/products">Clear filters</Link> : "Create the first product or import a file."}
          </AdminEmpty>
        </div>
      ) : null}
      {shown.length ? (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <caption className="text-sm text-muted">{shown.length} of {items.length} product{items.length === 1 ? "" : "s"}</caption>
            <thead><tr><th scope="col">Product</th><th scope="col">Variants</th><th scope="col">Images</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((item) => {
                const active = item.variants.filter((variant) => variant.isActive).length;
                const image = item.images[0];
                return (
                  <tr key={item.id}>
                    <th scope="row">
                      <div className="admin-cell-main">
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        {image ? <img className="admin-thumb" src={variantUrl(image.url, "thumb")} alt="" loading="lazy" width={44} height={44} /> :
                          <span className="admin-thumb" aria-hidden="true"><ImageOff size={16} /></span>}
                        <div>
                          <Link prefetch={false} className="admin-row-link" href={`/admin/products/${item.slug}`}>{item.name}</Link>
                          <span className="admin-code">/{item.slug}</span>
                        </div>
                      </div>
                    </th>
                    <td><span className="account-badge" data-tone={active ? "ok" : "warn"}>{active}/{item.variants.length} active</span></td>
                    <td><span className="account-badge" data-tone={item.images.length ? "ok" : "warn"}>{item.images.length} image{item.images.length === 1 ? "" : "s"}</span></td>
                    <td className="admin-cell-actions">
                      <Link prefetch={false} className="store-cta" data-variant="ghost" data-size="sm" href={`/admin/products/${item.slug}`}
                        aria-label={`Edit ${item.name}`}>Edit<ChevronRight size={14} aria-hidden="true" /></Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}
