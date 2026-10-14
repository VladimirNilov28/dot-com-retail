import type { Metadata } from "next";
import { AdminDenied } from "@/components/admin-denied";
import { ChevronRight, CornerDownRight, Folder, FolderTree, Plus, Trash2 } from "lucide-react";
import { AdminEmpty, AdminPageHeader, AdminPanel } from "@/components/admin-page-header";
import { AdminLoadError, AdminStatus } from "@/components/admin-status";
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
      <AdminPageHeader title="Categories" description="Organise the catalogue tree. Expand a category to rename it, change its slug, move it or delete it." />
      <AdminStatus params={params} />
      {failed ? <AdminLoadError>Categories could not be loaded. Retry shortly.</AdminLoadError> : null}

      <AdminPanel id="new-category" title="New category">
        <form action={createCategory} className="admin-form-grid">
          <label className="auth-field"><span className="text-sm">Name</span><input className="auth-input" name="name" required maxLength={255} /></label>
          <label className="auth-field"><span className="text-sm">Slug</span>
            <input className="auth-input" name="slug" required maxLength={255} pattern="[a-z0-9]+(-[a-z0-9]+)*" /></label>
          <label className="auth-field"><span className="text-sm">Parent</span>
            <select className="auth-input" name="parentId" defaultValue=""><option value="">Top level</option>{parentOptions(new Set())}</select></label>
          <button className="store-cta" data-variant="primary" type="submit"><Plus size={16} aria-hidden="true" />Create</button>
        </form>
      </AdminPanel>

      <section aria-labelledby="tree-heading" className="grid gap-3">
        <div className="admin-panel-head">
          <div>
            <h2 id="tree-heading" className="text-lg font-semibold">Category tree <span className="text-muted font-normal">· {tree.length}</span></h2>
            <p className="admin-panel-description">A category cannot move under itself or its own subcategories. Delete subcategories first; deleting a category only removes it from its products.</p>
          </div>
        </div>
        {!failed && tree.length === 0 ? <div className="admin-panel"><AdminEmpty icon={<FolderTree size={22} />} title="No categories yet" /></div> : null}
        {tree.length ? (
          <ul className="admin-list" aria-label="Categories">
            {tree.map((row) => (
              <li key={row.id}>
                <details className="admin-disclosure">
                  <summary style={{ paddingInlineStart: `${1 + row.depth * 1.5}rem` }}>
                    {row.depth ? <CornerDownRight size={15} className="admin-tree-indent" aria-hidden="true" /> : <Folder size={16} className="admin-tree-indent" aria-hidden="true" />}
                    <span className="font-medium">{row.name}</span>
                    <span className="admin-code">/{row.slug}</span>
                    <ChevronRight size={16} className="admin-chevron" aria-hidden="true" />
                  </summary>
                  <div className="admin-disclosure-body">
                    <form action={updateCategory} className="admin-form-grid">
                      <input type="hidden" name="categoryId" value={row.id} />
                      <label className="auth-field"><span className="text-sm">Name</span>
                        <input className="auth-input" name="name" defaultValue={row.name} required maxLength={255} /></label>
                      <label className="auth-field"><span className="text-sm">Slug</span>
                        <input className="auth-input" name="slug" defaultValue={row.slug} required maxLength={255} pattern="[a-z0-9]+(-[a-z0-9]+)*" /></label>
                      <label className="auth-field"><span className="text-sm">Parent</span>
                        <select className="auth-input" name="parentId" defaultValue={row.parent?.id ?? ""}>
                          <option value="">Top level</option>{parentOptions(blockedParents(categories, row.id))}
                        </select></label>
                      <button className="store-cta" data-variant="primary" type="submit">Save {row.name}</button>
                    </form>
                    <form action={deleteCategory} className="admin-form-actions">
                      <input type="hidden" name="categoryId" value={row.id} />
                      <label className="admin-check"><input type="checkbox" name="confirm" value="yes" required /> Confirm deleting {row.name}</label>
                      <button className="store-cta" data-variant="danger" data-size="sm" type="submit"><Trash2 size={14} aria-hidden="true" />Delete</button>
                    </form>
                  </div>
                </details>
              </li>
            ))}
          </ul>
        ) : null}
      </section>
    </div>
  );
}
