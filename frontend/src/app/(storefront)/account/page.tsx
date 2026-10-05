import type { Metadata } from "next";
import { UnavailablePage } from "@/components/unavailable-page";

export const metadata: Metadata = { title: "Account" };

export default function AccountPage() {
  return (
    <UnavailablePage
      title="Account"
      description="Account access is not available yet. Sign-in and account management will be added later."
    />
  );
}
