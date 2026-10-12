# Product media, brand and units (#96, #97, #65)

## Storage boundary

- Product images are uploaded through the admin UI (`/admin/products/<slug>`), never committed to Git and never placed in `frontend/public/`.
- Bytes live on the backend's durable media directory (`MEDIA_STORAGE_DIR`, default `./media-data`; compose mounts the `media_volume` volume at `/app/media-data`). `backend/media-data/` is git-ignored. Back this volume up with the database.
- The database stores only a generated UUID key (`<uuid>.jpg|png`), content type, byte size, pixel size, alt text and position (`product_images`). Filenames from the client are never used.
- Images attach to the **product**, not to a variant. Position 0 is the primary image.

## Endpoints

| Endpoint | Access |
| --- | --- |
| `POST /admin/media/products/{productId}/images` (multipart `file`, `altText`) | Bearer JWT with scope `product:write` and role `CATALOG_MANAGER` or `ADMIN` |
| `GET /media/products/{uuid}.{jpg,png}` (backend) | Public, registered keys only; `nosniff`, restrictive CSP, 1-year immutable cache |
| `GET /media/products/{key}` (storefront) | Same-origin proxy to the backend (`MEDIA_BACKEND_URL`, default `http://127.0.0.1:8080`) |

Upload validation: real content is inspected (JPEG/PNG magic bytes, decodable), max 5 MB, 200–8000 px per side, ≤ 40 megapixels, ≤ 12 images per product. Errors map to 400/404/413/415 JSON `{ "error": "…" }`. Deleting an image removes the file only when no other row references the key.

GraphQL (public read): `Product.images { id url altText width height position isPrimary }`, `Product.brand`. Admin mutations: `createBrand`, `updateProductImage`, `setPrimaryProductImage`, `reorderProductImages`, `deleteProductImage`.

## Admin access

The admin pages require role `ADMIN`/`CATALOG_MANAGER` and a session at assurance `aal2`. Staff MFA enrolment is not built yet (#80/#40), so for **local development only** `ADMIN_ALLOW_AAL1=1` relaxes the assurance check, and is ignored unless `STOREFRONT_ORIGIN` is an `http://localhost|127.0.0.1` origin. The backend independently enforces role and scope on every mutation and upload.

## Weight and dimensions

Canonical storage is integers: `weight_grams` (1–1,000,000) and `length_mm`/`width_mm`/`height_mm` (each 1–100,000; all three or none). `NULL` means "not specified" and is distinct from zero. Conversion is presentation-only: weight shows g (< 1000 g) or kg (up to 3 decimals), and oz (1 decimal, below 1 lb) or lb (2 decimals); dimensions show cm (1 decimal) and in (2 decimals) in L × W × H order, and only when all three are present.
