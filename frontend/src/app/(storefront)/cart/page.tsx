import type { Metadata } from "next";
import { UnavailablePage } from "@/components/unavailable-page";

export const metadata: Metadata = { title: "Cart" };

export default function CartPage() {
  return (
    <UnavailablePage
      title="Cart"
      description="Shopping cart features are not available yet. Cart contents, previews, and checkout will be added later."
    />
  );
}
