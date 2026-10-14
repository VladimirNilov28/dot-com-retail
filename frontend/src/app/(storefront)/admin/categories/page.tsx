import type { Metadata } from "next";
import { AdminDenied } from "@/components/admin-denied";
import { AdminStatus } from "@/components/admin-status";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { blockedParents, categoryTree, type CategoryNode } from "@/lib/admin/forms";
import { createCategory, deleteCategory, updateCategory } from "@/lib/admin/manage-actions";

export const metadata: Metadata = {
  title: "Admin · Categories",
  description: "Manage the catalogue category tree: create categories and subcategories, rename them, change slugs, move them under another parent, or delete unused ones.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

export default async function AdminCategories({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} next="/admin/categories" area="Category management" />;
  let categories: CategoryNode[] = [];
  let failed = false;
  try {
    categories = (await adminGraphql<{ categories: CategoryNode[] }>(access.session.accessToken,
      `query { categories { id name slug parent { id } } }`)).categories;
  } catch {
    failed = true;
  }
  const tree = categoryTree(categories);
  const parentOptions = (blocked: Set<string>) => tree.filter((row) => !blocked.has(row.id)).map((row) => (
    <option key={row.id} value={row.id}>{"— ".repeat(row.depth)}{row.name}</option>
  ));
  return (
    <div className="account-main">
      <h1 className="text-2xl font-semibold tracking-tight">Category management</h1>
      <AdminStatus params={params} />
      {failed ? <p role="alert" className="text-muted">Categories could not be loaded. Retry shortly.</p> : null}

      <section className="account-card" aria-labelledby="new-category">
        <h2 id="new-category" className="text-lg font-semibold">New category</h2>
        <form action={createCategory} className="admin-inline">
          <label className="auth-field"><span className="text-sm">Name</span><input className="auth-input" name="name" required maxLength={255} /></label>
          <label className="auth-field"><span className="text-sm">Slug</span>
            <input className="auth-input" name="slug" required maxLength={255} pattern="[a-z0-9]+(-[a-z0-9]+)*" /></label>
          <label className="auth-field"><span className="text-sm">Parent</span>
            <select className="auth-input" name="parentId" defaultValue=""><option value="">Top level</option>{parentOptions(new Set())}</select></label>
          <button className="store-cta" type="submit">Create</button>
        </form>
      </section>

      <section className="account-card" aria-labelledby="tree-heading">
        <h2 id="tree-heading" className="text-lg font-semibold">Category tree</h2>
        <p className="text-sm text-muted">A category cannot move under itself or its own subcategories. Delete subcategories first; deleting a category only removes it from its products.</p>
        {!failed && tree.length === 0 ? <p className="text-muted">No categories yet.</p> : null}
        <ul className="grid gap-4" aria-label="Categories">
          {tree.map((row) => (
            <li key={row.id} className="grid gap-2" style={{ paddingInlineStart: `${row.depth * 1.5}rem` }}>
              <form action={updateCategory} className="admin-inline">
                <input type="hidden" name="categoryId" value={row.id} />
                <label className="auth-field"><span className="text-sm">Name</span>
                  <input className="auth-input" name="name" defaultValue={row.name} required maxLength={255} /></label>
                <label className="auth-field"><span className="text-sm">Slug</span>
                  <input className="auth-input" name="slug" defaultValue={row.slug} required maxLength={255} pattern="[a-z0-9]+(-[a-z0-9]+)*" /></label>
                <label className="auth-field"><span className="text-sm">Parent</span>
                  <select className="auth-input" name="parentId" defaultValue={row.parent?.id ?? ""}>
                    <option value="">Top level</option>{parentOptions(blockedParents(categories, row.id))}
                  </select></label>
                <button className="store-cta" type="submit">Save {row.name}</button>
              </form>
              <form action={deleteCategory} className="admin-inline">
                <input type="hidden" name="categoryId" value={row.id} />
                <label className="admin-checks"><input type="checkbox" name="confirm" value="yes" required /> Confirm deleting {row.name}</label>
                <button className="store-cta" type="submit">Delete</button>
              </form>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
