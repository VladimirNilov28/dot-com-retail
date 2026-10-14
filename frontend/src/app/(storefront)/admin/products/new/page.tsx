import type { Metadata } from "next";
import Link from "next/link";
import { AdminDenied } from "@/components/admin-denied";
import { AdminStatus } from "@/components/admin-status";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { createProduct } from "@/lib/admin/actions";
import { categoryTree, type CategoryNode } from "@/lib/admin/forms";

export const metadata: Metadata = {
  title: "Admin · New product",
  description: "Create a catalogue product with a name, URL slug, description, brand and categories, then add sellable variants, prices, stock and images on the next page.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

export default async function NewProduct({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} next="/admin/products/new" />;
  let data: { brands: { id: string; name: string }[]; categories: CategoryNode[] } = { brands: [], categories: [] };
  let failed = false;
  try {
    data = await adminGraphql(access.session.accessToken, `query { brands { id name } categories { id name slug parent { id } } }`);
  } catch {
    failed = true;
  }
  return (
    <div className="account-main">
      <div>
        <Link prefetch={false} className="store-link" href="/admin/products">← Products</Link>
        <h1 className="text-2xl font-semibold tracking-tight">New product</h1>
      </div>
      <AdminStatus params={params} />
      {failed ? <p role="alert" className="text-muted">Brands and categories could not be loaded; you can still create the product and add them later.</p> : null}
      <form action={createProduct} className="account-card">
        <label className="auth-field"><span className="text-sm">Name</span>
          <input className="auth-input" name="name" required maxLength={255} /></label>
        <label className="auth-field"><span className="text-sm">URL slug (lowercase letters, digits and hyphens)</span>
          <input className="auth-input" name="newSlug" required maxLength={255} pattern="[a-z0-9]+(-[a-z0-9]+)*" /></label>
        <label className="auth-field"><span className="text-sm">Description</span>
          <textarea className="auth-input" name="description" rows={5} maxLength={10000} /></label>
        <div className="admin-inline">
          <label className="auth-field"><span className="text-sm">Brand</span>
            <select className="auth-input" name="brandId" defaultValue="">
              <option value="">Not specified</option>
              {data.brands.map((brand) => <option key={brand.id} value={brand.id}>{brand.name}</option>)}
            </select></label>
          <label className="auth-field"><span className="text-sm">Or add a new brand</span>
            <input className="auth-input" name="newBrand" maxLength={100} /></label>
        </div>
        <fieldset className="admin-checks">
          <legend className="text-sm">Categories</legend>
          {categoryTree(data.categories).map((category) => (
            <label key={category.id}><input type="checkbox" name="categoryIds" value={category.id} />
              {"— ".repeat(category.depth)}{category.name}</label>
          ))}
          {data.categories.length === 0 ? <span className="text-muted text-sm">No categories yet.</span> : null}
        </fieldset>
        <button className="store-cta justify-self-start" type="submit">Create product</button>
      </form>
    </div>
  );
}
