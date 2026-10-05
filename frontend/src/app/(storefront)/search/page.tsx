import type { Metadata } from "next";
import { UnavailablePage } from "@/components/unavailable-page";

export const metadata: Metadata = { title: "Search" };

export default function SearchPage() {
  return (
    <UnavailablePage
      title="Search"
      description="Product search is not available yet. There is no search form or quick-search overlay at this stage."
    />
  );
}
