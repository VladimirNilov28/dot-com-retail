"use client";

import { Button } from "@heroui/react";
import { useEffect, useId, useState } from "react";
import type { SecuritySettings } from "@/lib/auth/settings";
import { passwordGuidance } from "@/lib/auth/password";
import { ActionForm } from "./action-form";

const section = "/account/security";
const passwordErrors: Record<string, string> = {
  password_mismatch: "The passwords don't match.",
  minimum: "Use at least 8 bytes.",
  maximum: "Use at most 72 bytes.",
};

function StatusBadge({ on, onLabel = "On", offLabel = "Off" }: { on: boolean; onLabel?: string; offLabel?: string }) {
  return <span className="account-badge" data-tone={on ? "ok" : "off"}>{on ? onLabel : offLabel}</span>;
}

export function PasswordPanel({ settings, hasPassword, editable, notice }: {
  settings?: SecuritySettings; hasPassword: boolean; editable: boolean; notice?: string;
}) {
  const id = useId();
  const error = notice ? passwordErrors[notice] : undefined;
  return (
    <section className="account-card" aria-labelledby={`${id}-h`}>
      <div className="account-card-header">
        <div>
          <h2 id={`${id}-h`} className="text-lg font-semibold">Password</h2>
          <p className="text-sm text-muted">{hasPassword ? "Use your email or username and password to sign in." :
            "You sign in with Google. Add a password so you can also sign in without Google."}</p>
        </div>
        <StatusBadge on={hasPassword} onLabel="Set" offLabel="Not set" />
      </div>
      {editable && settings?.actions.includes("password") ? (
        <ActionForm endpoint="/auth/security" csrf={settings.csrf} label={hasPassword ? "Change password" : "Create password"}
          fields={{ flow: settings.id, action: "password", section }}>
          <p id={`${id}-g`} className="text-sm text-muted">{passwordGuidance}</p>
          <label className="auth-field" data-invalid={error ? "true" : undefined}>
            <span>{hasPassword ? "New password" : "Password"}</span>
            <input className="auth-input px-3" name="password" type="password" autoComplete="new-password" required
              aria-describedby={`${id}-g${error ? ` ${id}-e` : ""}`} aria-invalid={error ? true : undefined} />
          </label>
          <label className="auth-field" data-invalid={error ? "true" : undefined}>
            <span>Confirm password</span>
            <input className="auth-input px-3" name="password_confirm" type="password" autoComplete="new-password" required
              aria-invalid={error ? true : undefined} />
          </label>
          {error ? <p id={`${id}-e`} className="text-sm text-danger">{error}</p> : null}
          {hasPassword ? <p className="text-sm text-muted">Changing your password signs out your other sessions.</p> : null}
        </ActionForm>
      ) : null}
    </section>
  );
}

export function AuthenticatorPanel({ settings, enabled, editable, section = "/account/security", next }:
  { settings?: SecuritySettings; enabled: boolean; editable: boolean; section?: string; next?: string }) {
  const id = useId();
  const [visible, setVisible] = useState(true);
  useEffect(() => {
    const hide = () => { if (document.visibilityState === "hidden") setVisible(false); };
    document.addEventListener("visibilitychange", hide);
    return () => document.removeEventListener("visibilitychange", hide);
  }, []);
  const available = (action: SecuritySettings["actions"][number]) => editable && settings?.actions.includes(action);
  return (
    <section className="account-card" aria-labelledby={`${id}-h`}>
      <div className="account-card-header">
        <div>
          <h2 id={`${id}-h`} className="text-lg font-semibold">Authenticator app</h2>
          <p className="text-sm text-muted">{enabled ? "You'll enter a code from your app when you sign in." :
            "Add a second step to sign-in with an authenticator app."}</p>
        </div>
        <StatusBadge on={enabled} />
      </div>
      {settings && available("totp_confirm") ? (
        <div className="account-form">
          <p className="text-sm text-muted">Scan the QR code with your authenticator app, or enter the setup key. Keep the key private.</p>
          {visible && settings.qr ? (
            // Only a validated Kratos PNG data URI is accepted; no remote QR service.
            // eslint-disable-next-line @next/next/no-img-element
            <img src={settings.qr} alt="Authenticator setup QR code" className="account-qr" width={192} height={192} />
          ) : null}
          {visible && settings.secret ? <p className="text-sm">Setup key: <code className="break-all select-all">{settings.secret}</code></p> : null}
          {!visible ? <div><Button variant="secondary" onPress={() => setVisible(true)}>Show setup details</Button></div> : null}
          <ActionForm endpoint="/auth/security" csrf={settings.csrf} label="Turn on authenticator"
            fields={{ flow: settings.id, action: "totp_confirm", section, ...(next ? { next } : {}) }}>
            <label className="auth-field">
              <span>6-digit code from the app</span>
              <input className="auth-input px-3" name="totp_code" inputMode="numeric" autoComplete="one-time-code"
                pattern="[0-9]{6}" minLength={6} maxLength={6} required />
            </label>
          </ActionForm>
        </div>
      ) : null}
      {settings && available("totp_disable") ? (
        <ActionForm endpoint="/auth/security" csrf={settings.csrf} label="Remove authenticator" variant="secondary"
          fields={{ flow: settings.id, action: "totp_disable", section, acknowledge: "yes" }}
          confirm={{ title: "Remove authenticator?", action: "Remove",
            body: "Sign-in will no longer ask for an authenticator code, and your recovery codes will stop working." }} />
      ) : null}
    </section>
  );
}

export function RecoveryCodesPanel({ settings, enabled, remaining, totpEnabled, editable }: {
  settings?: SecuritySettings; enabled: boolean; remaining: number; totpEnabled: boolean; editable: boolean;
}) {
  const id = useId();
  const codes = settings?.codes.filter((code) => !code.used) ?? [];
  const [download, setDownload] = useState<string>();
  useEffect(() => {
    if (!codes.length) return;
    const contents = ["ByteCore recovery codes", "Use one only if you lose your authenticator app. Each code works once.", "",
      ...codes.map((code) => code.value)].join("\n");
    const url = URL.createObjectURL(new Blob([contents], { type: "text/plain;charset=utf-8" }));
    queueMicrotask(() => setDownload(url));
    return () => { URL.revokeObjectURL(url); setDownload(undefined); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings?.id]);
  const available = (action: SecuritySettings["actions"][number]) => editable && settings?.actions.includes(action);
  return (
    <section className="account-card" aria-labelledby={`${id}-h`}>
      <div className="account-card-header">
        <div>
          <h2 id={`${id}-h`} className="text-lg font-semibold">Recovery codes</h2>
          <p className="text-sm text-muted">If you lose your authenticator, sign in with your password, then use a recovery code to get back in and replace it.</p>
        </div>
        <StatusBadge on={enabled} onLabel={`${remaining} left`} offLabel="Off" />
      </div>
      {!totpEnabled ? <p className="text-sm text-muted">Turn on an authenticator app first.</p> : null}
      {codes.length ? (
        <div className="account-form">
          <p className="text-sm">Save these codes somewhere private. They won&apos;t be shown again.</p>
          <ul aria-label="New recovery codes" className="account-codes">
            {codes.map((code, index) => <li key={index} className="select-all">{code.value}</li>)}
          </ul>
          {download ? <a href={download} download="bytecore-recovery-codes.txt" className="store-cta justify-self-start">Download codes</a> : null}
        </div>
      ) : null}
      {settings && available("backup_confirm") ? (
        <ActionForm endpoint="/auth/security" csrf={settings.csrf} label="Activate these codes"
          fields={{ flow: settings.id, action: "backup_confirm", section }}>
          <label className="flex items-start gap-3 text-sm">
            <input type="checkbox" name="acknowledge" value="yes" required className="mt-1" />
            <span>I saved these codes. Activating them replaces any previous codes.</span>
          </label>
        </ActionForm>
      ) : settings && totpEnabled && available("backup_generate") ? (
        <ActionForm endpoint="/auth/security" csrf={settings.csrf} variant={enabled ? "secondary" : "primary"}
          label={enabled ? "Generate new codes" : "Create recovery codes"} pendingLabel="Generating…"
          fields={{ flow: settings.id, action: "backup_generate", section }} />
      ) : null}
      {settings && available("backup_disable") && !codes.length ? (
        <ActionForm endpoint="/auth/security" csrf={settings.csrf} label="Turn off recovery codes" variant="secondary"
          fields={{ flow: settings.id, action: "backup_disable", section, acknowledge: "yes" }}
          confirm={{ title: "Turn off recovery codes?", action: "Turn off",
            body: "If you lose your authenticator, you won't be able to recover your account with a code." }} />
      ) : null}
    </section>
  );
}
