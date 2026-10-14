"use server";

import { randomUUID } from "node:crypto";
import { redirect } from "next/navigation";
import { cancelOrderMutation, requestRefundMutation } from "./documents";
import { formField, isOrderReference, noteText } from "./model";
import { customerErrorCode, customerSession, orderGraphql } from "./server";

function reference(form: FormData): string {
  const publicId = formField(form, "publicId");
  if (!isOrderReference(publicId)) redirect("/account/orders");
  return publicId;
}

async function run(publicId: string, notice: string, work: (token: string) => Promise<void>): Promise<never> {
  const session = await customerSession();
  if (session.kind !== "ok") redirect(`/login?returnTo=${encodeURIComponent(`/account/orders/${publicId}`)}`);
  let outcome = `notice=${notice}`;
  try {
    await work(session.accessToken);
  } catch (error) {
    outcome = `error=${customerErrorCode(error)}`;
  }
  redirect(`/account/orders/${publicId}?${outcome}#status`);
}

export async function cancelMyOrder(form: FormData) {
  const publicId = reference(form);
  await run(publicId, "cancelled", async (token) => {
    await orderGraphql(token, cancelOrderMutation, { input: { publicId, requestId: randomUUID() } });
  });
}

export async function requestMyRefund(form: FormData) {
  const publicId = reference(form);
  let reason: string | null;
  try {
    reason = noteText(formField(form, "reason"), "Reason", true);
  } catch {
    redirect(`/account/orders/${publicId}?error=reason#status`);
  }
  await run(publicId, "refund_requested", async (token) => {
    await orderGraphql(token, requestRefundMutation, { input: { publicId, reason } });
  });
}
