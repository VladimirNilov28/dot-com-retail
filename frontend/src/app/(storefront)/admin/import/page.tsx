import type { Metadata } from "next";
import { AdminDenied } from "@/components/admin-denied";
import { AdminImportForm } from "@/components/admin-import-form";
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
      <h1 className="text-2xl font-semibold tracking-tight">Bulk product import</h1>
      <p className="text-sm text-muted">
        Imports are all-or-nothing: the file is checked completely (required fields, prices with at most two decimals, unique slugs and
        SKUs in the file and catalogue, known category slugs, dimensions) and nothing is saved unless every row is valid. Products are
        created new; existing slugs or SKUs are reported as errors rather than updated.
      </p>
      <details className="account-card">
        <summary>File format</summary>
        <p className="text-sm">CSV header (one row per variant; rows sharing a slug form one product):</p>
        <pre className="text-xs" style={{ overflowX: "auto" }}>slug,name,description,brand,categories,sku,price,barcode,weight_grams,length_mm,width_mm,height_mm,active,attr:color</pre>
        <p className="text-sm">Required: slug, name, sku, price. Separate several category slugs with “|”. Columns starting with “attr:” become variant attributes.
          JSON: {"{"}&quot;products&quot;: [{"{"}slug, name, description, brand, categories: [...], variants: [{"{"}sku, price, attributes, barcode, weightGrams, lengthMm, widthMm, heightMm, active{"}"}]{"}"}]{"}"}.</p>
      </details>
      <AdminImportForm />
    </div>
  );
}
