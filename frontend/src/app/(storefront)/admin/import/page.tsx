import type { Metadata } from "next";
import { AdminDenied } from "@/components/admin-denied";
import { AdminImportForm } from "@/components/admin-import-form";
import { AdminPageHeader, AdminPanel } from "@/components/admin-page-header";
import { adminAccess } from "@/lib/admin/gate";

export const metadata: Metadata = {
  title: "Admin · Bulk product import",
  description: "Upload a CSV or JSON file of products and variants. Every row is validated first; if any row has a problem, nothing is imported and each error is listed.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

export default async function AdminImport() {
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} next="/admin/import" area="Product import" />;
  return (
    <div className="account-main">
      <AdminPageHeader title="Bulk import" back={{ href: "/admin/products", label: "Products" }}
        description="Upload a CSV or JSON file of new products and variants. Imports are all-or-nothing: nothing is saved unless every row is valid." />
      <div className="admin-import-layout">
        <AdminImportForm />
        <AdminPanel id="format" title="File format"
          description="Checks: required fields, prices with at most two decimals, unique slugs and SKUs in the file and catalogue, known category slugs, dimensions. Existing slugs or SKUs are reported as errors, not updated.">
          <div className="grid gap-2">
            <h3 className="admin-legend">CSV</h3>
            <p className="admin-hint">One row per variant; rows sharing a slug form one product.</p>
            <pre className="admin-codeblock">slug,name,description,brand,categories,sku,price,barcode,weight_grams,length_mm,width_mm,height_mm,active,attr:color</pre>
            <p className="admin-hint">Required: slug, name, sku, price. Separate several category slugs with “|”. Columns starting with “attr:” become variant attributes.</p>
          </div>
          <div className="grid gap-2">
            <h3 className="admin-legend">JSON</h3>
            <pre className="admin-codeblock">{`{ "products": [ {
  "slug", "name", "description", "brand",
  "categories": [...],
  "variants": [ { "sku", "price", "attributes", "barcode",
    "weightGrams", "lengthMm", "widthMm", "heightMm", "active" } ]
} ] }`}</pre>
          </div>
        </AdminPanel>
      </div>
    </div>
  );
}
