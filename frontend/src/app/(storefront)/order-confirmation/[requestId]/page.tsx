import type { Metadata } from "next";
import { connection } from "next/server";
import { ConfirmationPageView } from "@/components/checkout/confirmation-page";

export const metadata: Metadata = { title: "Order confirmation", robots: { index: false, follow: false }, referrer: "no-referrer" };

export default async function ConfirmationPage({ params, searchParams }: {
  params: Promise<{ requestId: string }>; searchParams: Promise<{ guest?: string }>;
}) {
  await connection();
  const { requestId } = await params;
  const { guest } = await searchParams;
  return <ConfirmationPageView reference={requestId} guest={guest === "1"} />;
}
