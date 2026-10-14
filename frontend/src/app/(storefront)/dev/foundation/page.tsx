import type { Metadata } from "next";
import { FoundationDemo } from "@/components/foundation-demo";

export const metadata: Metadata = {
  title: "Component playground",
  description:
    "Internal HeroUI component and theme demonstration. Not part of the storefront.",
  robots: { index: false, follow: false },
};

export default function FoundationPlaygroundPage() {
  return (
    <div className="space-y-6">
      <header className="max-w-2xl space-y-3">
        <p className="text-sm font-semibold tracking-widest text-accent uppercase">
          Development
        </p>
        <h1 className="text-2xl leading-tight font-semibold tracking-tight sm:text-3xl">
          Component playground
        </h1>
        <p className="text-muted">
          The HeroUI v3 dark-theme demonstration from the project foundation. It
          is kept out of the storefront pages and is not indexed.
        </p>
      </header>

      <FoundationDemo />
    </div>
  );
}
