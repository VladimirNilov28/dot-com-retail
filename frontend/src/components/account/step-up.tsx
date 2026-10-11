import type { StepUpView } from "@/lib/auth/account";
import type { AccountSection } from "@/lib/auth/model";
import { ActionForm } from "./action-form";

/** Explains and drives the verification required before sensitive changes. */
export function StepUpCard({ stepUp, csrf, section }: { stepUp: StepUpView; csrf: string; section: AccountSection }) {
  if (stepUp.ready) return null;
  const reauth = `/auth/security-reauth?section=${encodeURIComponent(section)}`;
  return (
    <section className="account-card account-verify" aria-labelledby="verify-heading">
      <div>
        <h2 id="verify-heading" className="text-lg font-semibold">Verify it&apos;s you</h2>
        <p className="text-sm text-muted">
          {!stepUp.fresh ? "Sign in again to make security changes. Nothing has been changed." :
            stepUp.method === "authenticator" ? "Confirm your sign-in with your authenticator app to make changes." :
              "We'll email a one-time code to your account address to confirm these changes."}
        </p>
      </div>
      {!stepUp.fresh || stepUp.method === "authenticator" ? (
        // Auth endpoints require full navigation, not RSC fetches.
         
        <a className="store-cta justify-self-start" href={reauth}>{stepUp.fresh ? "Verify with authenticator" : "Confirm sign-in"}</a>
      ) : stepUp.codeSent ? (
        <div className="account-inline">
          <ActionForm endpoint="/auth/account" csrf={csrf} fields={{ action: "step_up_verify", section }} label="Verify code" pendingLabel="Verifying…">
            <label className="auth-field">
              <span>Email code</span>
              <input className="auth-input px-3" name="code" inputMode="numeric" autoComplete="one-time-code"
                pattern="[0-9]{6}" minLength={6} maxLength={6} required />
            </label>
          </ActionForm>
          <ActionForm endpoint="/auth/account" csrf={csrf} fields={{ action: "step_up_send", section }}
            label="Send a new code" pendingLabel="Sending…" variant="secondary" />
        </div>
      ) : (
        <ActionForm endpoint="/auth/account" csrf={csrf} fields={{ action: "step_up_send", section }}
          label="Email me a code" pendingLabel="Sending…" />
      )}
    </section>
  );
}
