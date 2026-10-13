import "server-only";
import { headers } from "next/headers";
import type { ReactNode } from "react";
import { AuthProblem } from "@/components/auth-page";
import { loadAccount, type AccountView } from "@/lib/auth/account";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie, hasSessionCookie, type AccountSection } from "@/lib/auth/model";
import { loadSecuritySettings, type SecuritySettings } from "@/lib/auth/settings";

export type AccountQuery = Record<string, string | string[] | undefined>;
export type AccountContext = { view: AccountView; settings?: SecuritySettings; query: AccountQuery };

export function AccountHeading({ title, description }: { title: string; description?: string }) {
  return (
    <header className="account-heading">
      <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{title}</h1>
      {description ? <p className="text-muted">{description}</p> : null}
    </header>
  );
}

function SignInPrompt({ section, title }: { section: string; title: string }) {
  return (
    <div className="account-main">
      <AccountHeading title={title} description="Sign in to manage your ByteCore account." />
      { }
      <a className="store-cta justify-self-start" href={`/login?returnTo=${encodeURIComponent(section)}`}>Sign in</a>
    </div>
  );
}

/** Resolves the signed-in customer's account view, or the page to show instead. */
export async function accountContext(section: AccountSection, title: string, search: Promise<AccountQuery>,
  options: { settings?: boolean; sessions?: boolean } = {}): Promise<AccountContext | { element: ReactNode }> {
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) return { element: <SignInPrompt section={section} title={title} /> };
  const query = await search;
  try {
    const browser = authCookie(request.get("cookie"), authConfig().sessionCookie);
    if (!browser) throw new AuthError("expired", 401);
    const view = await loadAccount(browser, options.sessions);
    let settings: SecuritySettings | undefined;
    if (options.settings) {
      if (query.reauth === "required" || !view.stepUp.fresh) {
        settings = undefined;
      } else {
        const flow = query.flow;
        if (Array.isArray(flow)) throw new AuthError("invalid_request", 400);
        settings = await loadSecuritySettings(browser, flow);
      }
    }
    return { view, settings, query };
  } catch (error) {
    const code = error instanceof AuthError ? error.code : "unavailable";
    if (code === "forbidden") return { element: (
      <div className="account-main">
        <AccountHeading title={title} description="Staff authenticator setup and verification live in the admin area." />
        <a className="store-cta justify-self-start" href="/admin/security">Open authenticator setup</a>
      </div>
    ) };
    if (code === "reauthentication_required") return { element: (
      <div className="account-main">
        <AccountHeading title="Confirm it's you" description="Security changes need a recent sign-in using your strongest available method. Nothing has been changed." />
        { }
        <a className="store-cta justify-self-start" href={`/auth/security-reauth?section=${encodeURIComponent(section)}`}>Confirm sign-in</a>
      </div>
    ) };
    console.error("account page reason", code);
    return { element: <AuthProblem code={code} returnTo={section} /> };
  }
}
