"use server";

import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { boundedInt, idPattern, importFormat, importLimits, price, slug, text } from "@/lib/admin/forms";
import { adminOnly, adminRoles } from "@/lib/admin/policy";

function id(form: FormData, name: string): string {
  const value = form.get(name);
  if (typeof value !== "string" || !idPattern.test(value)) throw new Error("Invalid identifier");
  return value;
}

/** Runs one staff write and returns to the page with a notice or a readable server error. */
async function act(path: string, roles: ReadonlySet<string>, work: (token: string, actorRole: string) => Promise<string>) {
  const access = await adminAccess(roles);
  if (access.kind !== "ok") redirect(path);
  let message: string;
  let failed = false;
  try {
    message = await work(access.session.accessToken, access.session.role);
  } catch (error) {
    failed = true;
    message = error instanceof Error ? error.message.slice(0, 300) : "Request failed";
  }
  revalidatePath(path);
  redirect(`${path}?${failed ? "error" : "notice"}=${encodeURIComponent(message)}#status`);
}

// ---- Categories ---------------------------------------------------------------------------

export async function createCategory(form: FormData) {
  await act("/admin/categories", adminRoles, async (token) => {
    const parent = String(form.get("parentId") ?? "");
    const input = { name: text(form.get("name"), "Name", 255, true), slug: slug(form.get("slug")), parentId: parent === "" ? null : id(form, "parentId") };
    await adminGraphql(token, `mutation($i: CreateCategoryInput!) { createCategory(input: $i) { id } }`, { i: input });
    revalidatePath("/catalog", "layout");
    return `Category ${input.name} created`;
  });
}

export async function updateCategory(form: FormData) {
  await act("/admin/categories", adminRoles, async (token) => {
    const parent = String(form.get("parentId") ?? "");
    const input: Record<string, unknown> = { name: text(form.get("name"), "Name", 255, true), slug: slug(form.get("slug")) };
    if (parent === "") input.clearParent = true;
    else input.parentId = id(form, "parentId");
    await adminGraphql(token, `mutation($c: ID!, $i: UpdateCategoryInput!) { updateCategory(categoryId: $c, input: $i) { id } }`,
      { c: id(form, "categoryId"), i: input });
    revalidatePath("/catalog", "layout");
    return "Category saved";
  });
}

export async function deleteCategory(form: FormData) {
  await act("/admin/categories", adminRoles, async (token) => {
    if (form.get("confirm") !== "yes") throw new Error("Tick the confirmation box to delete a category");
    await adminGraphql(token, `mutation($c: ID!) { deleteCategory(categoryId: $c) }`, { c: id(form, "categoryId") });
    revalidatePath("/catalog", "layout");
    return "Category deleted; its products stay in the catalog without this category";
  });
}

// ---- Delivery options ---------------------------------------------------------------------

function deliveryValues(form: FormData) {
  const min = boundedInt(form.get("estimatedMinDays"), "Minimum days", 0, 365);
  const max = boundedInt(form.get("estimatedMaxDays"), "Maximum days", 0, 365);
  if (min > max) throw new Error("Minimum days must not exceed maximum days");
  return {
    name: text(form.get("name"), "Name", 100, true),
    description: text(form.get("description"), "Description", 255),
    price: price(form.get("price"), true),
    estimatedMinDays: min,
    estimatedMaxDays: max,
    active: form.get("active") === "on",
    sortOrder: boundedInt(form.get("sortOrder"), "Sort order", 0, 1000),
  };
}

export async function createDeliveryOption(form: FormData) {
  await act("/admin/shipping", adminOnly, async (token) => {
    const code = String(form.get("code") ?? "");
    if (!["STANDARD", "EXPRESS", "PICKUP"].includes(code)) throw new Error("Choose a delivery method");
    await adminGraphql(token, `mutation($i: CreateDeliveryOptionInput!) { createDeliveryOption(input: $i) { id } }`,
      { i: { code, ...deliveryValues(form) } });
    return `${code} delivery option created`;
  });
}

export async function updateDeliveryOption(form: FormData) {
  await act("/admin/shipping", adminOnly, async (token) => {
    await adminGraphql(token, `mutation($id: ID!, $i: UpdateDeliveryOptionInput!) { updateDeliveryOption(id: $id, input: $i) { id } }`,
      { id: id(form, "optionId"), i: deliveryValues(form) });
    return "Delivery option saved; new checkouts use it immediately";
  });
}

export async function deleteDeliveryOption(form: FormData) {
  await act("/admin/shipping", adminOnly, async (token) => {
    if (form.get("confirm") !== "yes") throw new Error("Tick the confirmation box to delete a delivery option");
    await adminGraphql(token, `mutation($id: ID!) { deleteDeliveryOption(id: $id) }`, { id: id(form, "optionId") });
    return "Delivery option deleted; placed orders keep their own shipping snapshot";
  });
}

// ---- Users --------------------------------------------------------------------------------

const roles = ["USER", "SUPPORT", "CATALOG_MANAGER", "ORDER_MANAGER", "WAREHOUSE", "ADMIN"];

export async function changeUserRole(form: FormData) {
  const back = String(form.get("back") ?? "");
  const path = back.startsWith("/admin/users") ? back.split("#")[0].split("?")[0] : "/admin/users";
  await act(path, adminOnly, async (token) => {
    const role = String(form.get("role") ?? "");
    if (!roles.includes(role)) throw new Error("Choose a valid role");
    const data = await adminGraphql<{ updateUserRole: { username: string; role: string } }>(token,
      `mutation($u: ID!, $r: Role!) { updateUserRole(userId: $u, input: { role: $r }) { username role } }`, { u: id(form, "userId"), r: role });
    return `${data.updateUserRole.username} is now ${data.updateUserRole.role}. The change reaches their access token at its next refresh or sign-in.`;
  });
}

// ---- Bulk import --------------------------------------------------------------------------

export type ImportState = {
  status: "idle" | "success" | "failed";
  message: string;
  fileName?: string;
  counts?: { products: number; variants: number; brands: number };
  errors: { row: number; field: string; message: string }[];
};

export async function importProducts(_previous: ImportState, form: FormData): Promise<ImportState> {
  const access = await adminAccess(adminRoles);
  if (access.kind !== "ok") return { status: "failed", message: "Your session no longer has catalog access. Reload the page.", errors: [] };
  const file = form.get("file");
  if (!(file instanceof File) || file.size === 0) return { status: "failed", message: "Choose a CSV or JSON file to import", errors: [] };
  if (file.size > importLimits.bytes) return { status: "failed", message: "The file is larger than 1 MB", fileName: file.name, errors: [] };
  let format: "CSV" | "JSON";
  try {
    format = importFormat(file.name, form.get("format"));
  } catch (error) {
    return { status: "failed", message: (error as Error).message, fileName: file.name, errors: [] };
  }
  try {
    const data = await adminGraphql<{ importProducts: {
      success: boolean; productsCreated: number; variantsCreated: number; brandsCreated: number;
      errors: { row: number; field: string; message: string }[];
    } }>(access.session.accessToken,
      `mutation($f: ProductImportFormat!, $c: String!) { importProducts(format: $f, content: $c) {
        success productsCreated variantsCreated brandsCreated errors { row field message } } }`,
      { f: format, c: await file.text() });
    const result = data.importProducts;
    if (result.success) {
      revalidatePath("/admin/products");
      return {
        status: "success", fileName: file.name, errors: [],
        message: `Imported ${result.productsCreated} products and ${result.variantsCreated} variants.`,
        counts: { products: result.productsCreated, variants: result.variantsCreated, brands: result.brandsCreated },
      };
    }
    return {
      status: "failed", fileName: file.name, errors: result.errors,
      message: `Nothing was imported: ${result.errors.length} problem${result.errors.length === 1 ? "" : "s"} found. Fix the file and upload it again.`,
    };
  } catch (error) {
    return { status: "failed", fileName: file.name, errors: [], message: error instanceof Error ? error.message.slice(0, 300) : "Import failed" };
  }
}
