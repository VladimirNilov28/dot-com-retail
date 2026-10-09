import type { Metadata } from "next";
import { AuthProblem, type AuthSearch } from "@/components/auth-page";
import { PageContainer } from "@/components/page-container";
import { StoreHeader } from "@/components/store-header";
import { AuthError, formKind, safeReturnTo, type FormKind } from "@/lib/auth/model";

export const metadata: Metadata = { title: "Authentication request", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function AuthenticationError({ searchParams }: { searchParams: AuthSearch }) {
  const query = await searchParams;
  let reason = typeof query.reason === "string" ? query.reason : "invalid_request";
  let kind: FormKind = "login";
  let returnTo = "/";
  try {
    kind = formKind(query.kind ?? "login");
    returnTo = safeReturnTo(query.returnTo);
  } catch (error) {
    if (!(error instanceof AuthError)) throw error;
    console.error("authentication recovery destination rejected", error.code);
    reason = "invalid_request";
  }
  return <><StoreHeader /><main id="main-content" className="py-8 sm:py-12"><PageContainer>
    <AuthProblem code={reason} kind={kind} returnTo={returnTo} />
  </PageContainer></main></>;
}
