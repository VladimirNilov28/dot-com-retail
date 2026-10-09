import type { Metadata } from "next";
import { AuthPage, type AuthSearch } from "@/components/auth-page";

export const metadata: Metadata = { title: "Create account", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default function RegistrationPage({ searchParams }: { searchParams: AuthSearch }) {
  return <AuthPage kind="registration" searchParams={searchParams} />;
}
