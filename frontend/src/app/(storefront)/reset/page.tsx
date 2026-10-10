import type { Metadata } from "next";
import { AuthPage, type AuthSearch } from "@/components/auth-page";

export const metadata: Metadata = { title: "Reset password", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default function ResetPage({ searchParams }: { searchParams: AuthSearch }) {
  return <AuthPage kind="reset" searchParams={searchParams} />;
}
