import type { Metadata } from "next";
import Link from "next/link";
import { AdminDenied } from "@/components/admin-denied";
import { AdminPageHeader, AdminPanel } from "@/components/admin-page-header";
import { AdminLoadError, AdminStatus } from "@/components/admin-status";
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
      <AdminPageHeader title="New product" back={{ href: "/admin/products", label: "Products" }}
        description="Start with the basics. Variants, prices, stock and images are added on the next page." />
      <AdminStatus params={params} />
      {failed ? <AdminLoadError>Brands and categories could not be loaded; you can still create the product and add them later.</AdminLoadError> : null}
      <form action={createProduct} className="admin-form">
        <AdminPanel id="basics" title="Basics">
          <div className="admin-form-grid">
            <label className="auth-field"><span className="text-sm">Name</span>
              <input className="auth-input" name="name" required maxLength={255} /></label>
            <label className="auth-field"><span className="text-sm">URL slug</span>
              <input className="auth-input" name="newSlug" required maxLength={255} pattern="[a-z0-9]+(-[a-z0-9]+)*" aria-describedby="slug-hint" /></label>
            <p id="slug-hint" className="admin-hint admin-span-full">Lowercase letters, digits and hyphens, for example <code>acme-laptop-15</code>.</p>
            <label className="auth-field admin-span-full"><span className="text-sm">Description</span>
              <textarea className="auth-input" name="description" rows={5} maxLength={10000} /></label>
          </div>
        </AdminPanel>
        <AdminPanel id="brand" title="Brand">
          <div className="admin-form-grid">
            <label className="auth-field"><span className="text-sm">Existing brand</span>
              <select className="auth-input" name="brandId" defaultValue="">
                <option value="">Not specified</option>
                {data.brands.map((brand) => <option key={brand.id} value={brand.id}>{brand.name}</option>)}
              </select></label>
            <label className="auth-field"><span className="text-sm">Or add a new brand</span>
              <input className="auth-input" name="newBrand" maxLength={100} /></label>
          </div>
        </AdminPanel>
        <AdminPanel id="categories" title="Categories" description="Select every category the product should appear in.">
          <fieldset className="admin-checks">
            <legend className="sr-only">Categories</legend>
            {categoryTree(data.categories).map((category) => (
              <label key={category.id}><input type="checkbox" name="categoryIds" value={category.id} />
                {"— ".repeat(category.depth)}{category.name}</label>
            ))}
            {data.categories.length === 0 ? <span className="text-muted text-sm">No categories yet.</span> : null}
          </fieldset>
        </AdminPanel>
        <div className="admin-form-actions">
          <button className="store-cta" data-variant="primary" type="submit">Create product</button>
          <Link prefetch={false} className="store-cta" data-variant="ghost" href="/admin/products">Cancel</Link>
        </div>
      </form>
    </div>
  );
}
