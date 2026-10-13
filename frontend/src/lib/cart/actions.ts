"use server";

import { headers } from "next/headers";
import { authConfig } from "@/lib/auth/config";
import { authCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";
import { hiveConfig, requestHive } from "@/lib/graphql/server";
import { myCartAdd, myCartRemove, myCartUpdate, myCartView } from "@/lib/graphql/operations";
import { cartErrorMessage, toCartView, type CartSnapshot } from "./model";

/** `guest` means no customer session; staff and anonymous visitors both use the guest cart. */
export type UserCartResult = { mode: "guest" } | { mode: "user"; snapshot: CartSnapshot; note?: string };

async function customerToken(): Promise<string | null> {
  try {
    const id = authCookie((await headers()).get("cookie"), authConfig().sessionCookie);
    if (!id) return null;
    const { state, user } = await authenticatedState(id);
    return user.role === "USER" ? state.tokens?.accessToken ?? null : null;
  } catch {
    return null;
  }
}

async function readUserCart(accessToken: string, note?: string): Promise<UserCartResult> {
  const { endpoint, origin } = hiveConfig(process.env);
  const { result } = await requestHive(myCartView, {}, { endpoint, origin }, { kind: "authenticated", accessToken });
  if (result.status !== "success") {
    console.error("Cart read failed:", result.status === "failure" ? result.error.kind : "partial");
    return { mode: "user", snapshot: { status: "error", message: cartErrorMessage("unavailable") } };
  }
  return { mode: "user", snapshot: { status: "ok", cart: toCartView(result.data.myCart) }, note };
}

export async function loadCart(): Promise<UserCartResult> {
  const token = await customerToken();
  return token ? readUserCart(token) : { mode: "guest" };
}

export type CartMutation =
  | { kind: "add"; variantId: string; quantity: number }
  | { kind: "update"; id: string; quantity: number }
  | { kind: "remove"; id: string };

export async function mutateUserCart(mutation: CartMutation): Promise<UserCartResult & { error?: string }> {
  const token = await customerToken();
  if (!token) return { mode: "guest" };
  const { endpoint, origin } = hiveConfig(process.env);
  const context = { kind: "authenticated", accessToken: token } as const;
  const config = { endpoint, origin };
  // Mutations are never retried automatically; the caller decides after an uncertain failure.
  const { result } = mutation.kind === "add" ?
    await requestHive(myCartAdd, { input: { productVariantId: mutation.variantId, quantity: mutation.quantity } }, config, context) :
    mutation.kind === "update" ?
      await requestHive(myCartUpdate, { id: mutation.id, input: { quantity: mutation.quantity } }, config, context) :
      await requestHive(myCartRemove, { id: mutation.id }, config, context);
  const error = result.status === "success" ? undefined : cartErrorMessage(result.status === "failure" ? result.error.kind : "graphql");
  if (result.status !== "success") console.error("Cart mutation failed:", result.status === "failure" ? result.error.kind : "partial");
  const fresh = await readUserCart(token);
  return error ? { ...fresh, error } : fresh;
}
