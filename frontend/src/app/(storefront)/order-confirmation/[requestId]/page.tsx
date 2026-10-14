import type { Metadata } from "next";
import { connection } from "next/server";
import { ConfirmationPageView } from "@/components/checkout/confirmation-page";
import { CheckoutIdentityBoundary } from "@/components/checkout/identity-boundary";

export const metadata: Metadata = { title: "Order confirmation", robots: { index: false, follow: false }, referrer: "no-referrer" };

export default async function ConfirmationPage({ params, searchParams }: {
  params: Promise<{ requestId: string }>; searchParams: Promise<{ guest?: string }>;
}) {
  await connection();
  const { requestId } = await params;
  const { guest } = await searchParams;
  return <CheckoutIdentityBoundary><ConfirmationPageView reference={requestId} guest={guest === "1"} /></CheckoutIdentityBoundary>;
}
