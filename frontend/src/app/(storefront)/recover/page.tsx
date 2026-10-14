import type { Metadata } from "next";
import { AuthPage, type AuthSearch } from "@/components/auth-page";

export const metadata: Metadata = { title: "Recover your account", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default function RecoveryPage({ searchParams }: { searchParams: AuthSearch }) {
  return <AuthPage kind="recovery" searchParams={searchParams} />;
}
