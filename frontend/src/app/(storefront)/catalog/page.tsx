import type { Metadata } from "next";
import { UnavailablePage } from "@/components/unavailable-page";

export const metadata: Metadata = { title: "Catalog" };

export default function CatalogPage() {
  return (
    <UnavailablePage
      title="Catalog"
      description="Product browsing is not available yet. This page will become the catalog when that feature is ready."
    />
  );
}
