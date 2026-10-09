import type { Metadata } from "next";
import { AuthPage, type AuthSearch } from "@/components/auth-page";

export const metadata: Metadata = { title: "Verify email", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default function VerificationPage({ searchParams }: { searchParams: AuthSearch }) {
  return <AuthPage kind="verification" searchParams={searchParams} />;
}
