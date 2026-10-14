import type { Metadata } from "next";
import { AuthPage, type AuthSearch } from "@/components/auth-page";

export const metadata: Metadata = { title: "Sign in", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default function LoginPage({ searchParams }: { searchParams: AuthSearch }) {
  return <AuthPage kind="login" searchParams={searchParams} />;
}
