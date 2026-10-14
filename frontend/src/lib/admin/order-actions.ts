"use server";

import { randomUUID } from "node:crypto";
import { redirect } from "next/navigation";
import { adminAccess } from "@/lib/admin/gate";
import { orderRoles } from "@/lib/admin/policy";
import {
  approveRefundMutation, rejectRefundMutation, setTrackingMutation, staffCancelMutation, updateStatusMutation,
} from "@/lib/orders/documents";
import { formField, fulfillmentTargets, isOrderReference, noteText, trackingInput } from "@/lib/orders/model";
import { orderGraphql } from "@/lib/orders/server";

function reference(form: FormData): string {
  const publicId = formField(form, "publicId");
  if (!isOrderReference(publicId)) redirect("/admin/orders");
  return publicId;
}

async function run(form: FormData, work: (token: string, publicId: string) => Promise<string>): Promise<never> {
  const publicId = reference(form);
  const access = await adminAccess(orderRoles);
  if (access.kind !== "ok") redirect(`/admin/orders/${publicId}`);
  let outcome: string;
  try {
    outcome = `notice=${encodeURIComponent(await work(access.session.accessToken, publicId))}`;
  } catch (error) {
    outcome = `error=${encodeURIComponent(error instanceof Error ? error.message.slice(0, 200) : "Request failed")}`;
  }
  redirect(`/admin/orders/${publicId}?${outcome}#status`);
}

export async function staffUpdateStatus(form: FormData) {
  await run(form, async (token) => {
    const orderId = formField(form, "orderId");
    const status = formField(form, "status");
    if (!/^[1-9][0-9]{0,18}$/.test(orderId)) throw new Error("Invalid order");
    if (!fulfillmentTargets.has(status)) throw new Error("Unsupported status");
    const note = noteText(formField(form, "note"), "Note", false);
    await orderGraphql(token, updateStatusMutation, { orderId, input: { status, note } });
    return `Order moved to ${status.toLowerCase()}`;
  });
}

export async function staffSetTracking(form: FormData) {
  await run(form, async (token, publicId) => {
    await orderGraphql(token, setTrackingMutation, { input: { publicId, ...trackingInput(form) } });
    return "Tracking saved";
  });
}

export async function staffCancel(form: FormData) {
  await run(form, async (token, publicId) => {
    await orderGraphql(token, staffCancelMutation, { input: { publicId, requestId: randomUUID() } });
    return "Order cancelled; stock released and any captured payment queued for refund";
  });
}

function requestId(form: FormData): string {
  const value = formField(form, "requestId");
  if (!isOrderReference(value)) throw new Error("Invalid refund request");
  return value;
}

export async function staffApproveRefund(form: FormData) {
  await run(form, async (token) => {
    const note = noteText(formField(form, "note"), "Note", false);
    await orderGraphql(token, approveRefundMutation, { input: { requestId: requestId(form), note } });
    return "Refund approved and queued with the payment provider";
  });
}

export async function staffRejectRefund(form: FormData) {
  await run(form, async (token) => {
    const note = noteText(formField(form, "note"), "Rejection note", true);
    await orderGraphql(token, rejectRefundMutation, { input: { requestId: requestId(form), note } });
    return "Refund request rejected";
  });
}
