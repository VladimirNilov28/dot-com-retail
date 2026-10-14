import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AdminDenied } from "@/components/admin-denied";
import { AdminStatus, AttributeRows } from "@/components/admin-status";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import {
  createVariant, deleteProduct, deleteVariant, makePrimary, moveImage, removeImage, saveBrand, saveVariantMeasures, setStock,
  updateAlt, updateProductDetails, updateVariant, uploadImage,
} from "@/lib/admin/actions";
import { categoryTree, type CategoryNode } from "@/lib/admin/forms";

export const metadata: Metadata = {
  title: "Admin · Edit product",
  description: "Edit one catalogue product: details, categories, brand, sellable variants with prices, attributes and active state, stock per warehouse, and product images.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

type Image = { id: string; url: string; altText: string; width: number; height: number; position: number; isPrimary: boolean };
type Variant = {
  id: string; sku: string; price: string | number; barcode: string | null; attributes: Record<string, unknown>; isActive: boolean;
  weightGrams: number | null; lengthMm: number | null; widthMm: number | null; heightMm: number | null;
};
type Product = {
  id: string; name: string; slug: string; description: string | null; brand: { id: string } | null; categories: { id: string }[];
  images: Image[]; variants: Variant[];
};
type Stock = { warehouses: { id: string; name: string }[]; product: { variants: { id: string; inventory: { warehouse: { id: string }; quantity: number }[] }[] } | null };

const query = `query($slug: String!) {
  product(slug: $slug) { id name slug description brand { id } categories { id }
    images { id url altText width height position isPrimary }
    variants { id sku price barcode attributes isActive weightGrams lengthMm widthMm heightMm } }
  brands { id name }
  categories { id name slug parent { id } }
}`;

// Separate request: stock needs warehouse scopes that catalog managers do not hold.
const stockQuery = `query($slug: String!) { warehouses { id name } product(slug: $slug) { variants { id inventory { warehouse { id } quantity } } } }`;

const value = (n: number | null) => (n === null ? "" : String(n));

export default async function AdminProduct({ params, searchParams }: {
  params: Promise<{ slug: string }>; searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { slug } = await params;
  const query_ = await searchParams;
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} next={`/admin/products/${encodeURIComponent(slug)}`} />;
  let data: { product: Product | null; brands: { id: string; name: string }[]; categories: CategoryNode[] };
  try {
    data = await adminGraphql(access.session.accessToken, query, { slug });
  } catch {
    return (
      <section className="account-main"><h1 className="text-2xl font-semibold">Product management</h1>
        <p role="alert" className="text-muted">The product could not be loaded. Retry shortly.</p>
        <Link prefetch={false} className="store-link" href="/admin/products">Back to products</Link></section>
    );
  }
  const { product, brands, categories } = data;
  if (!product) notFound();
  let stock: Stock | null = null;
  if (access.session.role === "ADMIN") {
    stock = await adminGraphql<Stock>(access.session.accessToken, stockQuery, { slug }).catch(() => null);
  }
  const selected = new Set(product.categories.map((category) => category.id));
  const order = product.images.map((image) => image.id).join(",");
  return (
    <div className="account-main">
      <div>
        <Link prefetch={false} className="store-link" href="/admin/products">← Products</Link>
        <h1 className="text-2xl font-semibold tracking-tight">{product.name}</h1>
      </div>
      <AdminStatus params={query_} />

      <section className="account-card" aria-labelledby="details-heading">
        <h2 id="details-heading" className="text-lg font-semibold">Details</h2>
        <form action={updateProductDetails} className="grid gap-3">
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <label className="auth-field"><span className="text-sm">Name</span>
            <input className="auth-input" name="name" defaultValue={product.name} required maxLength={255} /></label>
          <label className="auth-field"><span className="text-sm">URL slug</span>
            <input className="auth-input" name="newSlug" defaultValue={product.slug} required maxLength={255} pattern="[a-z0-9]+(-[a-z0-9]+)*" /></label>
          <label className="auth-field"><span className="text-sm">Description</span>
            <textarea className="auth-input" name="description" rows={5} maxLength={10000} defaultValue={product.description ?? ""} /></label>
          <fieldset className="admin-checks">
            <legend className="text-sm">Categories</legend>
            {categoryTree(categories).map((category) => (
              <label key={category.id}><input type="checkbox" name="categoryIds" value={category.id} defaultChecked={selected.has(category.id)} />
                {"— ".repeat(category.depth)}{category.name}</label>
            ))}
          </fieldset>
          <button className="store-cta justify-self-start" type="submit">Save details</button>
        </form>
      </section>

      <section className="account-card" aria-labelledby="variants-heading">
        <h2 id="variants-heading" className="text-lg font-semibold">Variants</h2>
        <p className="text-sm text-muted">Prices are in EUR with at most two decimals. Inactive variants are hidden from the storefront and cannot be bought.</p>
        {product.variants.length === 0 ? <p className="text-muted">No variants yet. Add one below so the product can be sold.</p> : null}
        {product.variants.map((variant) => (
          <div key={variant.id} className="grid gap-2">
            <form action={updateVariant} className="grid gap-2">
              <input type="hidden" name="slug" value={product.slug} />
              <input type="hidden" name="variantId" value={variant.id} />
              <h3 className="admin-sku font-semibold">{variant.sku} <span className="account-badge" data-tone={variant.isActive ? "ok" : "off"}>{variant.isActive ? "Active" : "Inactive"}</span></h3>
              <div className="admin-inline">
                <label className="auth-field"><span className="text-sm">SKU</span>
                  <input className="auth-input" name="sku" defaultValue={variant.sku} required maxLength={255} /></label>
                <label className="auth-field"><span className="text-sm">Price (EUR)</span>
                  <input className="auth-input" name="price" defaultValue={String(variant.price)} required inputMode="decimal" /></label>
                <label className="auth-field"><span className="text-sm">Barcode</span>
                  <input className="auth-input" name="barcode" defaultValue={variant.barcode ?? ""} maxLength={64} /></label>
                <label className="admin-checks"><input type="checkbox" name="isActive" defaultChecked={variant.isActive} /> Active</label>
              </div>
              <AttributeRows values={variant.attributes ?? {}} extra={1} />
              <button className="store-cta justify-self-start" type="submit">Save variant</button>
            </form>
            <form action={deleteVariant}>
              <input type="hidden" name="slug" value={product.slug} />
              <input type="hidden" name="variantId" value={variant.id} />
              <button className="store-cta" type="submit">Delete variant {variant.sku}</button>
            </form>
          </div>
        ))}
        <form action={createVariant} className="grid gap-2" aria-labelledby="new-variant-heading">
          <h3 id="new-variant-heading" className="font-semibold">Add a variant</h3>
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <div className="admin-inline">
            <label className="auth-field"><span className="text-sm">SKU</span><input className="auth-input" name="sku" required maxLength={255} /></label>
            <label className="auth-field"><span className="text-sm">Price (EUR)</span><input className="auth-input" name="price" required inputMode="decimal" /></label>
            <label className="auth-field"><span className="text-sm">Barcode</span><input className="auth-input" name="barcode" maxLength={64} /></label>
          </div>
          <div className="admin-inline">
            {([["weightGrams", "Weight (g)"], ["lengthMm", "Length (mm)"], ["widthMm", "Width (mm)"], ["heightMm", "Height (mm)"]] as const).map(([name, label]) => (
              <label key={name} className="auth-field"><span className="text-sm">{label}</span>
                <input className="auth-input" name={name} inputMode="numeric" pattern="[0-9]*" /></label>
            ))}
          </div>
          <AttributeRows values={{}} extra={3} />
          <label className="admin-checks"><input type="checkbox" name="isActive" defaultChecked /> Active</label>
          <button className="store-cta justify-self-start" type="submit">Add variant</button>
        </form>
      </section>

      <section className="account-card" aria-labelledby="images-heading">
        <h2 id="images-heading" className="text-lg font-semibold">Images</h2>
        <p className="text-sm text-muted">JPEG or PNG, up to 5 MB, 200–8000 px. The first image is the primary image.</p>
        {product.images.length === 0 ? <p className="text-muted">No images yet. Storefront shows a neutral placeholder.</p> : (
          <ol className="admin-images">
            {product.images.map((image, index) => (
              <li key={image.id} className="admin-image">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={image.url} alt={image.altText} width={image.width} height={image.height} loading="lazy" />
                <div className="admin-image-body">
                  <p className="text-sm">{image.isPrimary ? <strong>Primary</strong> : `Position ${index + 1}`} · {image.width}×{image.height}</p>
                  <form action={updateAlt} className="admin-inline">
                    <input type="hidden" name="slug" value={product.slug} />
                    <input type="hidden" name="imageId" value={image.id} />
                    <label className="auth-field"><span className="text-sm">Alternative text</span>
                      <input className="auth-input" name="altText" defaultValue={image.altText} maxLength={300} required /></label>
                    <button className="store-cta" type="submit">Save text</button>
                  </form>
                  <div className="admin-inline">
                    {([["up", "Move up", index === 0], ["down", "Move down", index === product.images.length - 1]] as const).map(([direction, label, disabled]) => (
                      <form key={direction} action={moveImage}>
                        <input type="hidden" name="slug" value={product.slug} />
                        <input type="hidden" name="productId" value={product.id} />
                        <input type="hidden" name="imageId" value={image.id} />
                        <input type="hidden" name="order" value={order} />
                        <input type="hidden" name="direction" value={direction} />
                        <button className="store-cta" type="submit" disabled={disabled}>{label}</button>
                      </form>
                    ))}
                    {image.isPrimary ? null : (
                      <form action={makePrimary}>
                        <input type="hidden" name="slug" value={product.slug} />
                        <input type="hidden" name="imageId" value={image.id} />
                        <button className="store-cta" type="submit">Make primary</button>
                      </form>
                    )}
                    <form action={removeImage}>
                      <input type="hidden" name="slug" value={product.slug} />
                      <input type="hidden" name="imageId" value={image.id} />
                      <button className="store-cta" type="submit">Remove</button>
                    </form>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        )}
        <form action={uploadImage} className="admin-inline" encType="multipart/form-data">
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <label className="auth-field"><span className="text-sm">Image file</span>
            <input className="auth-input" type="file" name="file" accept="image/jpeg,image/png" required /></label>
          <label className="auth-field"><span className="text-sm">Alternative text (optional)</span>
            <input className="auth-input" name="altText" maxLength={300} /></label>
          <button className="store-cta" type="submit">Upload image</button>
        </form>
      </section>

      <section className="account-card" aria-labelledby="brand-heading">
        <h2 id="brand-heading" className="text-lg font-semibold">Brand</h2>
        <form action={saveBrand} className="admin-inline">
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <label className="auth-field"><span className="text-sm">Brand</span>
            <select className="auth-input" name="brandId" defaultValue={product.brand?.id ?? ""}>
              <option value="">Not specified</option>
              {brands.map((brand) => <option key={brand.id} value={brand.id}>{brand.name}</option>)}
            </select></label>
          <label className="auth-field"><span className="text-sm">Or add a new brand</span>
            <input className="auth-input" name="newBrand" maxLength={120} /></label>
          <button className="store-cta" type="submit">Save brand</button>
        </form>
      </section>

      <section className="account-card" aria-labelledby="measures-heading">
        <h2 id="measures-heading" className="text-lg font-semibold">Variant weight and dimensions</h2>
        <p className="text-sm text-muted">Stored as grams and millimetres. Leave dimensions empty if unknown; enter all three or none.</p>
        {product.variants.map((variant) => (
          <form key={variant.id} action={saveVariantMeasures} className="admin-inline">
            <input type="hidden" name="slug" value={product.slug} />
            <input type="hidden" name="variantId" value={variant.id} />
            <strong className="admin-sku">{variant.sku}</strong>
            {([["weightGrams", "Weight (g)", variant.weightGrams], ["lengthMm", "Length (mm)", variant.lengthMm],
              ["widthMm", "Width (mm)", variant.widthMm], ["heightMm", "Height (mm)", variant.heightMm]] as const).map(([name, label, current]) => (
              <label key={name} className="auth-field"><span className="text-sm">{label}</span>
                <input className="auth-input" name={name} inputMode="numeric" pattern="[0-9]*" defaultValue={value(current)} /></label>
            ))}
            <button className="store-cta" type="submit">Save</button>
          </form>
        ))}
      </section>

      <section className="account-card" aria-labelledby="stock-heading">
        <h2 id="stock-heading" className="text-lg font-semibold">Stock per warehouse</h2>
        {access.session.role !== "ADMIN" ? <p className="text-muted">Stock levels are managed by warehouse staff or an administrator.</p> :
          !stock ? <p role="alert" className="text-muted">Stock could not be loaded. Retry shortly.</p> :
            stock.warehouses.length === 0 ? <p className="text-muted">No warehouses exist yet.</p> :
              product.variants.map((variant) => {
                const levels = stock.product?.variants.find((entry) => entry.id === variant.id)?.inventory ?? [];
                return (
                  <div key={variant.id} className="grid gap-2">
                    <h3 className="admin-sku font-semibold">{variant.sku}</h3>
                    {stock.warehouses.map((warehouse) => (
                      <form key={warehouse.id} action={setStock} className="admin-inline">
                        <input type="hidden" name="slug" value={product.slug} />
                        <input type="hidden" name="variantId" value={variant.id} />
                        <input type="hidden" name="warehouseId" value={warehouse.id} />
                        <label className="auth-field"><span className="text-sm">{warehouse.name} quantity</span>
                          <input className="auth-input" name="quantity" inputMode="numeric" pattern="[0-9]*" required
                            defaultValue={String(levels.find((level) => level.warehouse.id === warehouse.id)?.quantity ?? 0)} /></label>
                        <button className="store-cta" type="submit">Save stock</button>
                      </form>
                    ))}
                  </div>
                );
              })}
      </section>

      <section className="account-card" aria-labelledby="delete-heading">
        <h2 id="delete-heading" className="text-lg font-semibold">Delete product</h2>
        <p className="text-sm text-muted">Deletion is permanent. Products or variants with order or cart history cannot be deleted; mark their variants inactive instead.</p>
        <form action={deleteProduct} className="admin-inline">
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <label className="admin-checks"><input type="checkbox" name="confirm" value="yes" required /> I understand this cannot be undone</label>
          <button className="store-cta" type="submit">Delete product</button>
        </form>
      </section>
    </div>
  );
}
