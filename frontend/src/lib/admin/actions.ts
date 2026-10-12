"use server";

import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { moveId, optionalInt } from "@/lib/admin/policy";
import { mediaBackendUrl } from "@/lib/media/config";

const idPattern = /^[1-9][0-9]{0,18}$/;

function id(form: FormData, name: string): string {
  const value = form.get(name);
  if (typeof value !== "string" || !idPattern.test(value)) throw new Error("Invalid identifier");
  return value;
}

function slugOf(form: FormData): string {
  const value = form.get("slug");
  if (typeof value !== "string" || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value)) throw new Error("Invalid product");
  return value;
}

async function authorize() {
  const access = await adminAccess();
  if (access.kind !== "ok") redirect("/admin/products");
  return access.session.accessToken;
}

async function run(form: FormData, work: (token: string) => Promise<string>) {
  const slug = slugOf(form);
  const token = await authorize();
  let notice: string;
  let failed = false;
  try {
    notice = await work(token);
  } catch (error) {
    failed = true;
    notice = error instanceof Error ? error.message.slice(0, 200) : "Request failed";
  }
  revalidatePath(`/products/${slug}`);
  redirect(`/admin/products/${slug}?${failed ? "error" : "notice"}=${encodeURIComponent(notice)}#status`);
}

const imageFields = "id url altText width height position isPrimary";

export async function uploadImage(form: FormData) {
  await run(form, async (token) => {
    const file = form.get("file");
    if (!(file instanceof File) || file.size === 0) throw new Error("Choose an image file to upload");
    const body = new FormData();
    body.set("file", file);
    body.set("altText", String(form.get("altText") ?? "").trim().slice(0, 300));
    const response = await fetch(`${mediaBackendUrl()}/admin/media/products/${id(form, "productId")}/images`, {
      method: "POST", body, cache: "no-store", headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) {
      const detail = (await response.json().catch(() => ({}))) as { error?: string };
      throw new Error(detail.error ?? `Upload failed (${response.status})`);
    }
    return "Image uploaded";
  });
}

export async function updateAlt(form: FormData) {
  await run(form, async (token) => {
    await adminGraphql(token, `mutation($id: ID!, $alt: String!) { updateProductImage(imageId: $id, altText: $alt) { ${imageFields} } }`,
      { id: id(form, "imageId"), alt: String(form.get("altText") ?? "").trim() });
    return "Alternative text saved";
  });
}

export async function makePrimary(form: FormData) {
  await run(form, async (token) => {
    await adminGraphql(token, `mutation($id: ID!) { setPrimaryProductImage(imageId: $id) { ${imageFields} } }`, { id: id(form, "imageId") });
    return "Primary image changed";
  });
}

export async function moveImage(form: FormData) {
  await run(form, async (token) => {
    const order = String(form.get("order") ?? "").split(",").filter(Boolean);
    if (!order.every((value) => idPattern.test(value))) throw new Error("Invalid order");
    const next = moveId(order, id(form, "imageId"), form.get("direction") === "up" ? -1 : 1);
    if (!next) return "Already at the end";
    await adminGraphql(token, `mutation($p: ID!, $ids: [ID!]!) { reorderProductImages(productId: $p, imageIds: $ids) { ${imageFields} } }`,
      { p: id(form, "productId"), ids: next });
    return "Gallery order saved";
  });
}

export async function removeImage(form: FormData) {
  await run(form, async (token) => {
    await adminGraphql(token, `mutation($id: ID!) { deleteProductImage(imageId: $id) }`, { id: id(form, "imageId") });
    return "Image removed";
  });
}

export async function saveBrand(form: FormData) {
  await run(form, async (token) => {
    const productId = id(form, "productId");
    const name = String(form.get("newBrand") ?? "").trim();
    const selected = String(form.get("brandId") ?? "");
    if (name) {
      const data = await adminGraphql<{ createBrand: { id: string } }>(token, `mutation($n: String!) { createBrand(name: $n) { id } }`, { n: name });
      await adminGraphql(token, `mutation($p: ID!, $b: ID!) { updateProduct(productId: $p, input: { brandId: $b }) { id } }`, { p: productId, b: data.createBrand.id });
    } else if (selected === "") {
      await adminGraphql(token, `mutation($p: ID!) { updateProduct(productId: $p, input: { clearBrand: true }) { id } }`, { p: productId });
    } else {
      if (!idPattern.test(selected)) throw new Error("Invalid brand");
      await adminGraphql(token, `mutation($p: ID!, $b: ID!) { updateProduct(productId: $p, input: { brandId: $b }) { id } }`, { p: productId, b: selected });
    }
    return "Brand saved";
  });
}

export async function saveVariantMeasures(form: FormData) {
  await run(form, async (token) => {
    const weight = optionalInt(form.get("weightGrams"), 1_000_000);
    const length = optionalInt(form.get("lengthMm"), 100_000);
    const width = optionalInt(form.get("widthMm"), 100_000);
    const height = optionalInt(form.get("heightMm"), 100_000);
    const dims = [length, width, height];
    const clearDimensions = dims.every((value) => value === null);
    if (!clearDimensions && dims.some((value) => value === null)) throw new Error("Enter all three dimensions, or leave all empty");
    const input: Record<string, unknown> = clearDimensions ? { clearDimensions: true } : { lengthMm: length, widthMm: width, heightMm: height };
    if (weight !== null) input.weightGrams = weight;
    await adminGraphql(token, `mutation($v: ID!, $i: UpdateProductVariantInput!) { updateProductVariant(variantId: $v, input: $i) { id } }`,
      { v: id(form, "variantId"), i: input });
    return "Variant measurements saved";
  });
}
