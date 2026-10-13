import type { Metadata } from "next";
import { connection } from "next/server";
import { CheckoutPageView } from "@/components/checkout/checkout-page";

export const metadata: Metadata = { title: "Checkout", robots: { index: false, follow: false }, referrer: "no-referrer" };

export default async function CheckoutPage() {
  await connection();
  return <CheckoutPageView />;
}
