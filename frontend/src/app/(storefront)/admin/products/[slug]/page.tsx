import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AdminDenied } from "@/components/admin-denied";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import {
  makePrimary, moveImage, removeImage, saveBrand, saveVariantMeasures, updateAlt, uploadImage,
} from "@/lib/admin/actions";

export const metadata: Metadata = { title: "Admin · Product media", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

type Image = { id: string; url: string; altText: string; width: number; height: number; position: number; isPrimary: boolean };
type Variant = { id: string; sku: string; weightGrams: number | null; lengthMm: number | null; widthMm: number | null; heightMm: number | null };
type Product = { id: string; name: string; slug: string; brand: { id: string } | null; images: Image[]; variants: Variant[] };

const query = `query($slug: String!) {
  product(slug: $slug) { id name slug brand { id }
    images { id url altText width height position isPrimary }
    variants { id sku weightGrams lengthMm widthMm heightMm } }
  brands { id name }
}`;

const value = (n: number | null) => (n === null ? "" : String(n));

export default async function AdminProduct({ params, searchParams }: {
  params: Promise<{ slug: string }>; searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { slug } = await params;
  const query_ = await searchParams;
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} />;
  let data: { product: Product | null; brands: { id: string; name: string }[] };
  try {
    data = await adminGraphql(access.session.accessToken, query, { slug });
  } catch {
    return (
      <section className="account-main"><h1 className="text-2xl font-semibold">Product management</h1>
        <p role="alert" className="text-muted">The product could not be loaded. Retry shortly.</p>
        <Link prefetch={false} className="store-link" href="/admin/products">Back to products</Link></section>
    );
  }
  const { product, brands } = data;
  if (!product) notFound();
  const notice = typeof query_.notice === "string" ? query_.notice : null;
  const error = typeof query_.error === "string" ? query_.error : null;
  const order = product.images.map((image) => image.id).join(",");
  return (
    <div className="account-main">
      <div>
        <Link prefetch={false} className="store-link" href="/admin/products">← Products</Link>
        <h1 className="text-2xl font-semibold tracking-tight">{product.name}</h1>
      </div>
      <div id="status" tabIndex={-1}>
        {notice ? <p role="status" className="account-badge" data-tone="ok">{notice}</p> : null}
        {error ? <p role="alert" className="account-badge" data-tone="off">{error}</p> : null}
      </div>

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
    </div>
  );
}
