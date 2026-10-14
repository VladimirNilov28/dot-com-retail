import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AdminDenied } from "@/components/admin-denied";
import { ArrowDown, ArrowUp, ChevronRight, ExternalLink, ImageOff, ImageUp, Plus, Star, Trash2 } from "lucide-react";
import { AdminEmpty, AdminPageHeader, AdminPanel } from "@/components/admin-page-header";
import { AdminLoadError, AdminStatus, AttributeRows } from "@/components/admin-status";
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
      <div className="account-main">
        <AdminPageHeader title="Product" back={{ href: "/admin/products", label: "Products" }} />
        <AdminLoadError>The product could not be loaded. Retry shortly.</AdminLoadError>
      </div>
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
  const activeVariants = product.variants.filter((variant) => variant.isActive).length;
  const sections = [["details", "Details"], ["variants", "Variants"], ["images", "Images"], ["brand", "Brand"],
    ["measures", "Measurements"], ["stock", "Stock"], ["danger", "Delete"]] as const;
  return (
    <div className="account-main">
      <AdminPageHeader title={product.name} back={{ href: "/admin/products", label: "Products" }}
        meta={<>
          <span className="admin-code">/{product.slug}</span>
          <span className="account-badge" data-tone={activeVariants ? "ok" : "warn"}>{activeVariants}/{product.variants.length} variants active</span>
          <span className="account-badge" data-tone={product.images.length ? "ok" : "warn"}>{product.images.length} image{product.images.length === 1 ? "" : "s"}</span>
        </>}
        actions={activeVariants ? (
          <Link prefetch={false} className="store-cta" href={`/products/${product.slug}`}><ExternalLink size={16} aria-hidden="true" />View in store</Link>
        ) : null} />
      <AdminStatus params={query_} />
      <nav aria-label="Product sections" className="admin-tabs">
        {sections.map(([id, label]) => <a key={id} href={`#${id}`}>{label}</a>)}
      </nav>

      <AdminPanel id="details" title="Details" description="Name, address and description shown on the product page, plus its categories.">
        <form action={updateProductDetails} className="admin-form">
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <div className="admin-form-grid">
            <label className="auth-field"><span className="text-sm">Name</span>
              <input className="auth-input" name="name" defaultValue={product.name} required maxLength={255} /></label>
            <label className="auth-field"><span className="text-sm">URL slug</span>
              <input className="auth-input" name="newSlug" defaultValue={product.slug} required maxLength={255} pattern="[a-z0-9]+(-[a-z0-9]+)*" /></label>
            <label className="auth-field admin-span-full"><span className="text-sm">Description</span>
              <textarea className="auth-input" name="description" rows={6} maxLength={10000} defaultValue={product.description ?? ""} /></label>
          </div>
          <fieldset className="admin-checks">
            <legend className="admin-legend">Categories</legend>
            {categoryTree(categories).map((category) => (
              <label key={category.id}><input type="checkbox" name="categoryIds" value={category.id} defaultChecked={selected.has(category.id)} />
                {"— ".repeat(category.depth)}{category.name}</label>
            ))}
          </fieldset>
          <div className="admin-form-actions"><button className="store-cta" data-variant="primary" type="submit">Save details</button></div>
        </form>
      </AdminPanel>

      <AdminPanel id="variants" title="Variants" description="Prices are in EUR with at most two decimals. Inactive variants are hidden from the storefront and cannot be bought.">
        {product.variants.length === 0 ? <AdminEmpty title="No variants yet">Add one below so the product can be sold.</AdminEmpty> : null}
        {product.variants.map((variant) => (
          <div key={variant.id} className="admin-subcard">
            <div className="admin-subcard-head">
              <h3 className="admin-sku font-semibold">{variant.sku}</h3>
              <span className="account-badge" data-tone={variant.isActive ? "ok" : "warn"}>{variant.isActive ? "Active" : "Inactive"}</span>
            </div>
            <form action={updateVariant} className="admin-form">
              <input type="hidden" name="slug" value={product.slug} />
              <input type="hidden" name="variantId" value={variant.id} />
              <div className="admin-form-grid">
                <label className="auth-field"><span className="text-sm">SKU</span>
                  <input className="auth-input" name="sku" defaultValue={variant.sku} required maxLength={255} /></label>
                <label className="auth-field"><span className="text-sm">Price (EUR)</span>
                  <input className="auth-input" name="price" defaultValue={String(variant.price)} required inputMode="decimal" /></label>
                <label className="auth-field"><span className="text-sm">Barcode</span>
                  <input className="auth-input" name="barcode" defaultValue={variant.barcode ?? ""} maxLength={64} /></label>
                <label className="admin-check"><input type="checkbox" name="isActive" defaultChecked={variant.isActive} /> Active</label>
              </div>
              <AttributeRows values={variant.attributes ?? {}} extra={1} />
              <div className="admin-form-actions"><button className="store-cta" data-variant="primary" type="submit">Save variant</button></div>
            </form>
            <form action={deleteVariant} className="admin-form-actions">
              <input type="hidden" name="slug" value={product.slug} />
              <input type="hidden" name="variantId" value={variant.id} />
              <button className="store-cta" data-variant="danger" data-size="sm" type="submit"><Trash2 size={14} aria-hidden="true" />Delete variant {variant.sku}</button>
            </form>
          </div>
        ))}
        <details className="admin-subcard admin-disclosure" open={product.variants.length === 0}>
          <summary className="font-semibold"><Plus size={16} aria-hidden="true" />Add a variant<ChevronRight size={16} className="admin-chevron" aria-hidden="true" /></summary>
          <form action={createVariant} className="admin-form" aria-label="Add a variant">
            <input type="hidden" name="slug" value={product.slug} />
            <input type="hidden" name="productId" value={product.id} />
            <div className="admin-form-grid">
              <label className="auth-field"><span className="text-sm">SKU</span><input className="auth-input" name="sku" required maxLength={255} /></label>
              <label className="auth-field"><span className="text-sm">Price (EUR)</span><input className="auth-input" name="price" required inputMode="decimal" /></label>
              <label className="auth-field"><span className="text-sm">Barcode</span><input className="auth-input" name="barcode" maxLength={64} /></label>
              {([["weightGrams", "Weight (g)"], ["lengthMm", "Length (mm)"], ["widthMm", "Width (mm)"], ["heightMm", "Height (mm)"]] as const).map(([name, label]) => (
                <label key={name} className="auth-field"><span className="text-sm">{label}</span>
                  <input className="auth-input" name={name} inputMode="numeric" pattern="[0-9]*" /></label>
              ))}
            </div>
            <AttributeRows values={{}} extra={3} />
            <label className="admin-check justify-self-start"><input type="checkbox" name="isActive" defaultChecked /> Active</label>
            <div className="admin-form-actions"><button className="store-cta" data-variant="primary" type="submit">Add variant</button></div>
          </form>
        </details>
      </AdminPanel>

      <AdminPanel id="images" title="Images" description="JPEG or PNG, up to 5 MB, 200–8000 px. The first image is the primary image.">
        {product.images.length === 0 ? <AdminEmpty icon={<ImageOff size={22} />} title="No images yet">The storefront shows a neutral placeholder.</AdminEmpty> : (
          <ol className="admin-images">
            {product.images.map((image, index) => (
              <li key={image.id} className="admin-image">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={image.url} alt={image.altText} width={image.width} height={image.height} loading="lazy" />
                <div className="admin-image-body">
                  <p className="admin-image-flag">
                    {image.isPrimary ? <span className="account-badge" data-tone="ok">Primary</span> : <span>Position {index + 1}</span>}
                    <span>{image.width}×{image.height}</span>
                  </p>
                  <form action={updateAlt} className="admin-inline">
                    <input type="hidden" name="slug" value={product.slug} />
                    <input type="hidden" name="imageId" value={image.id} />
                    <label className="auth-field"><span className="text-sm">Alternative text</span>
                      <input className="auth-input" name="altText" defaultValue={image.altText} maxLength={300} required /></label>
                    <button className="store-cta" type="submit">Save text</button>
                  </form>
                  <div className="admin-inline">
                    {([["up", "Move up", index === 0, ArrowUp], ["down", "Move down", index === product.images.length - 1, ArrowDown]] as const).map(([direction, label, disabled, Icon]) => (
                      <form key={direction} action={moveImage}>
                        <input type="hidden" name="slug" value={product.slug} />
                        <input type="hidden" name="productId" value={product.id} />
                        <input type="hidden" name="imageId" value={image.id} />
                        <input type="hidden" name="order" value={order} />
                        <input type="hidden" name="direction" value={direction} />
                        <button className="store-cta" data-size="sm" type="submit" disabled={disabled}><Icon size={14} aria-hidden="true" />{label}</button>
                      </form>
                    ))}
                    {image.isPrimary ? null : (
                      <form action={makePrimary}>
                        <input type="hidden" name="slug" value={product.slug} />
                        <input type="hidden" name="imageId" value={image.id} />
                        <button className="store-cta" data-size="sm" type="submit"><Star size={14} aria-hidden="true" />Make primary</button>
                      </form>
                    )}
                    <form action={removeImage}>
                      <input type="hidden" name="slug" value={product.slug} />
                      <input type="hidden" name="imageId" value={image.id} />
                      <button className="store-cta" data-variant="danger" data-size="sm" type="submit"><Trash2 size={14} aria-hidden="true" />Remove</button>
                    </form>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        )}
        <form action={uploadImage} className="admin-dropzone" encType="multipart/form-data">
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <div className="admin-form-grid">
            <label className="auth-field"><span className="text-sm">Image file</span>
              <input className="auth-input" type="file" name="file" accept="image/jpeg,image/png" required /></label>
            <label className="auth-field"><span className="text-sm">Alternative text (optional)</span>
              <input className="auth-input" name="altText" maxLength={300} /></label>
          </div>
          <div className="admin-form-actions"><button className="store-cta" data-variant="primary" type="submit"><ImageUp size={16} aria-hidden="true" />Upload image</button></div>
        </form>
      </AdminPanel>

      <AdminPanel id="brand" title="Brand">
        <form action={saveBrand} className="admin-form">
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <div className="admin-form-grid">
            <label className="auth-field"><span className="text-sm">Brand</span>
              <select className="auth-input" name="brandId" defaultValue={product.brand?.id ?? ""}>
                <option value="">Not specified</option>
                {brands.map((brand) => <option key={brand.id} value={brand.id}>{brand.name}</option>)}
              </select></label>
            <label className="auth-field"><span className="text-sm">Or add a new brand</span>
              <input className="auth-input" name="newBrand" maxLength={120} /></label>
          </div>
          <div className="admin-form-actions"><button className="store-cta" data-variant="primary" type="submit">Save brand</button></div>
        </form>
      </AdminPanel>

      <AdminPanel id="measures" title="Weight and dimensions" description="Stored as grams and millimetres; the storefront also shows imperial units. Enter all three dimensions or none.">
        {product.variants.length === 0 ? <p className="admin-hint">Add a variant first.</p> : null}
        {product.variants.map((variant) => (
          <form key={variant.id} action={saveVariantMeasures} className="admin-subcard">
            <input type="hidden" name="slug" value={product.slug} />
            <input type="hidden" name="variantId" value={variant.id} />
            <strong className="admin-sku">{variant.sku}</strong>
            <div className="admin-form-grid">
              {([["weightGrams", "Weight (g)", variant.weightGrams], ["lengthMm", "Length (mm)", variant.lengthMm],
                ["widthMm", "Width (mm)", variant.widthMm], ["heightMm", "Height (mm)", variant.heightMm]] as const).map(([name, label, current]) => (
                <label key={name} className="auth-field"><span className="text-sm">{label}</span>
                  <input className="auth-input" name={name} inputMode="numeric" pattern="[0-9]*" defaultValue={value(current)} /></label>
              ))}
              <button className="store-cta" type="submit">Save {variant.sku}</button>
            </div>
          </form>
        ))}
      </AdminPanel>

      <AdminPanel id="stock" title="Stock per warehouse">
        {access.session.role !== "ADMIN" ? <p className="admin-hint">Stock levels are managed by warehouse staff or an administrator.</p> :
          !stock ? <AdminLoadError>Stock could not be loaded. Retry shortly.</AdminLoadError> :
            stock.warehouses.length === 0 ? <AdminEmpty title="No warehouses exist yet" /> :
              product.variants.map((variant) => {
                const levels = stock.product?.variants.find((entry) => entry.id === variant.id)?.inventory ?? [];
                return (
                  <div key={variant.id} className="admin-subcard">
                    <h3 className="admin-sku font-semibold">{variant.sku}</h3>
                    <div className="admin-form-grid">
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
                  </div>
                );
              })}
      </AdminPanel>

      <AdminPanel id="danger" tone="danger" title="Delete product"
        description="Deletion is permanent. Products or variants with order or cart history cannot be deleted; mark their variants inactive instead.">
        <form action={deleteProduct} className="admin-form-actions">
          <input type="hidden" name="slug" value={product.slug} />
          <input type="hidden" name="productId" value={product.id} />
          <label className="admin-check"><input type="checkbox" name="confirm" value="yes" required /> I understand this cannot be undone</label>
          <button className="store-cta" data-variant="danger" type="submit"><Trash2 size={16} aria-hidden="true" />Delete product</button>
        </form>
      </AdminPanel>
    </div>
  );
}
