# Product media, brand and units (#96, #97, #65)

## Storage boundary

- Product images are uploaded through the admin UI (`/admin/products/<slug>`), never committed to Git and never placed in `frontend/public/`.
- Bytes live on the backend's durable media directory (`MEDIA_STORAGE_DIR`, default `./media-data`; compose mounts the `media_volume` volume at `/app/media-data`). `backend/media-data/` is git-ignored. Back this volume up with the database.
- Each upload is stored as the untouched **original** at `<MEDIA_STORAGE_DIR>/<key>` plus three re-encoded delivery **variants** at `<MEDIA_STORAGE_DIR>/variants/{thumb,medium,full}/<key>` (see "Image variants").
- The database stores only a generated UUID key (`<uuid>.jpg|png`), content type, byte size, pixel size, alt text and position (`product_images`). Filenames from the client are never used.
- Images attach to the **product**, not to a variant. Position 0 is the primary image.

## Endpoints

| Endpoint | Access |
| --- | --- |
| `POST /admin/media/products/{productId}/images` (multipart `file`, `altText`) | Bearer JWT with scope `product:write` and role `CATALOG_MANAGER` or `ADMIN` |
| `GET /media/products/{uuid}.{jpg,png}[?size=thumb\|medium\|full]` (backend) | Public, registered keys only; `nosniff`, restrictive CSP, 1-year immutable cache. `size` defaults to `full`; any other value is `400 {"error": …}` |
| `GET /media/products/{key}[?size=…]` (storefront) | Same-origin proxy to the backend (`MEDIA_BACKEND_URL`, default `http://127.0.0.1:8080`); validates `size` (400) and the key (404) before forwarding |

Upload validation: real content is inspected (JPEG/PNG magic bytes, decodable), max 5 MB, 200–8000 px per side, ≤ 40 megapixels, ≤ 12 images per product. Errors map to 400/404/413/415 JSON `{ "error": "…" }`. Deleting an image removes the original and all variants only when no other row references the key.

## Image variants

| `size` | Longest edge | Used for |
| --- | --- | --- |
| `thumb` | ≤ 320 px | Product cards (1x), cart/mini-cart thumbnails, PDP gallery thumbnails, admin lists |
| `medium` | ≤ 800 px | Product cards (2x / wide cards), PDP main image default `src` |
| `full` (default) | ≤ 2000 px | PDP main image on large/high-density screens |

- Variants keep the aspect ratio, are **never upscaled** (a 640×480 upload yields 640×480 for `medium` and `full`), keep the source format/content type (JPEG → JPEG at quality 0.85, PNG → PNG with transparency), and are produced by decoding and re-encoding the pixels with Java ImageIO, so EXIF/GPS/text metadata is stripped. No extra library is used.
- Example (unit tests): a 3000×2000 JPEG gives 320×213 / 800×533 / 2000×1333; a 600×1200 PNG gives a 160×320 thumbnail.
- The original is kept only as the master for regeneration and is never served directly; a request without `size` gets the `full` variant.
- **Upload** generates all three variants before anything is stored; if the pixels cannot be decoded (e.g. a CMYK JPEG that passes header validation) the upload is rejected with 400 and nothing is written.
- **Backfill / lazy generation**: uploads that predate variants have only the original on disk. The first request for a missing variant generates it from the original, writes it atomically (temp file + rename in the same directory) and reuses it afterwards. Generation is serialized per key+size and at most two images are decoded concurrently. If a legacy original cannot be decoded, its bytes are served unchanged rather than failing. No startup job or migration is needed; opening the storefront once warms the cache.
- **Delete** of the last row referencing a key removes the original and every variant (best effort; a failure leaves an unreferenced file, never a dangling row).
- GraphQL is unchanged: `ProductImage.url` is the base URL, and the frontend builds `?size=` URLs and `srcSet` width descriptors via `frontend/src/lib/media/variants.ts` using the original `width`/`height`.

## Uploading images on a fresh stack (reviewers)

1. Sign in as a staff account with role `ADMIN` or `CATALOG_MANAGER` (staff MFA/aal2 required; see `docs/review-setup.md`).
2. Open **Admin → Products**, pick a product, and scroll to the **Images** panel (`/admin/products/<slug>#images`).
3. Choose a JPEG or PNG (≤ 5 MB, 200–8000 px per side, ≤ 40 MP), in **Image file**, optionally enter alternative text (defaults to the product name), and click **Upload image**.
4. The first image is primary. Use **Make primary**, the move controls and **Remove** in the same panel; edit alt text inline.
5. Check the storefront: cards and the cart show the `thumb` variant, the product page shows `medium`/`full`. In DevTools, image requests carry `?size=…`.

GraphQL (public read): `Product.images { id url altText width height position isPrimary }`, `Product.brand`. Admin mutations: `createBrand`, `updateProductImage`, `setPrimaryProductImage`, `reorderProductImages`, `deleteProductImage`.

## Admin access

The admin pages require role `ADMIN`/`CATALOG_MANAGER` and a session at assurance `aal2`. Staff MFA enrolment is not built yet (#80/#40), so for **local development only** `ADMIN_ALLOW_AAL1=1` relaxes the assurance check, and is ignored unless `STOREFRONT_ORIGIN` is an `http://localhost|127.0.0.1` origin. The backend independently enforces role and scope on every mutation and upload.

## Weight and dimensions

Canonical storage is integers: `weight_grams` (1–1,000,000) and `length_mm`/`width_mm`/`height_mm` (each 1–100,000; all three or none). `NULL` means "not specified" and is distinct from zero. Conversion is presentation-only: weight shows g (< 1000 g) or kg (up to 3 decimals), and oz (1 decimal, below 1 lb) or lb (2 decimals); dimensions show cm (1 decimal) and in (2 decimals) in L × W × H order, and only when all three are present.
