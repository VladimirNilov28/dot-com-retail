"use client";

import { Button } from "@heroui/react";
import { useEffect, useState, type ReactNode } from "react";
import type { SecurityAction, SecuritySettings as Settings } from "@/lib/auth/settings";

function SecurityForm({ settings, action, children, label }: {
  settings: Settings; action: SecurityAction; children?: ReactNode; label: string;
}) {
  const [pending, setPending] = useState(false);
  return (
    <form action="/auth/security" method="post" className="space-y-4" onSubmit={() => setPending(true)}>
      <input type="hidden" name="_csrf" value={settings.csrf} />
      <input type="hidden" name="flow" value={settings.id} />
      <input type="hidden" name="action" value={action} />
      {children}
      <Button type="submit" className="auth-submit" isDisabled={pending}>
        {pending ? "Saving…" : label}
      </Button>
    </form>
  );
}

export function SecuritySettings({ settings }: { settings: Settings }) {
  const [download, setDownload] = useState<string>();
  const [visible, setVisible] = useState(true);
  useEffect(() => {
    if (!settings.codes.length) return;
    const contents = ["ByteCore backup codes", "Keep these offline. Each unused code can be used once.", "",
      ...settings.codes.filter((code) => !code.used).map((code) => code.value)].join("\n");
    const url = URL.createObjectURL(new Blob([contents], { type: "text/plain;charset=utf-8" }));
    queueMicrotask(() => setDownload(url));
    return () => { URL.revokeObjectURL(url); setDownload(undefined); };
  }, [settings.id, settings.codes]);
  useEffect(() => {
    const hide = () => { if (document.visibilityState === "hidden") setVisible(false); };
    document.addEventListener("visibilitychange", hide);
    return () => document.removeEventListener("visibilitychange", hide);
  }, []);
  const available = (action: SecurityAction) => settings.actions.includes(action);
  return (
    <div className="space-y-6">
      {settings.invalid ? <p role="alert" className="text-danger">The change was not accepted. Check your authenticator code and try again.</p> : null}
      {settings.saved && !settings.invalid ? <p role="status">Your security settings have been updated.</p> : null}
      <section className="auth-panel space-y-4" aria-labelledby="authenticator-heading">
        <h2 id="authenticator-heading" className="text-xl font-semibold tracking-tight">Authenticator app</h2>
        <p className="text-muted">{settings.totpEnabled ? "Enabled. You will need a code when you sign in." :
          "Optional. Add an authenticator app for an extra layer of security."}</p>
        {available("totp_confirm") ? <>
          <p className="text-sm text-muted">Scan the QR code in your authenticator app, or enter the setup key manually. Keep this key private.</p>
          {visible && settings.qr ? (
            // Only a validated Kratos PNG data URI is accepted; no remote QR service.
            // eslint-disable-next-line @next/next/no-img-element
            <img src={settings.qr} alt="Authenticator setup QR code" className="max-w-full" width={256} height={256} />
          ) : null}
          {visible && settings.secret ? <p className="text-sm">Setup key: <code className="break-all select-all">{settings.secret}</code></p> : null}
          {!visible ? <Button variant="secondary" onPress={() => setVisible(true)}>Show setup details</Button> : null}
          <SecurityForm settings={settings} action="totp_confirm" label="Confirm authenticator">
            <label className="auth-field block space-y-2">
              <span>Authenticator code</span>
              <input className="auth-input px-3" name="totp_code" type="text" inputMode="numeric"
                autoComplete="one-time-code" pattern="[0-9]{6}" minLength={6} maxLength={6} required />
            </label>
          </SecurityForm>
        </> : null}
        {available("totp_disable") ? <SecurityForm settings={settings} action="totp_disable" label="Disable authenticator">
          <label className="flex items-start gap-3 text-sm">
            <input type="checkbox" name="acknowledge" value="yes" required className="mt-1" />
            <span>I understand that removing my authenticator reduces account protection. Backup codes remain enabled until removed separately.</span>
          </label>
        </SecurityForm> : null}
      </section>
      <section className="auth-panel space-y-4" aria-labelledby="backup-heading">
        <h2 id="backup-heading" className="text-xl font-semibold tracking-tight">Backup codes</h2>
        <p className="text-muted">Use a backup code if you cannot access your authenticator. Each code works once. Save them somewhere private, away from this device.</p>
        <p className="text-sm">{settings.backupEnabled ? "Backup codes are enabled." : "Backup codes are not yet enabled."}</p>
        {settings.codes.length ? <>
          {visible ? <ul aria-label="Backup codes" className="grid grid-cols-1 gap-2 font-mono text-sm sm:grid-cols-2">
            {settings.codes.map((code, index) => <li key={index} className="break-all select-all">
              {code.used ? <><s>{code.value}</s> — used</> : code.value}
            </li>)}
          </ul> : <p className="text-sm text-muted">Codes hidden for privacy.</p>}
          <div className="flex flex-wrap gap-3">
            <Button variant="secondary" onPress={() => setVisible(!visible)}>{visible ? "Hide codes" : "Show codes"}</Button>
            {download ? <a href={download} download="bytecore-backup-codes.txt" className="store-cta-secondary">Save codes</a> : null}
          </div>
        </> : null}
        {available("backup_confirm") ? <SecurityForm settings={settings} action="backup_confirm" label="Activate saved backup codes">
          <p className="text-sm text-muted">Generated codes do not work until you activate them. Activation replaces any previous codes.</p>
          <label className="flex items-start gap-3 text-sm">
            <input type="checkbox" name="acknowledge" value="yes" required className="mt-1" />
            <span>I saved these codes in a safe place.</span>
          </label>
        </SecurityForm> : null}
        {available("backup_reveal") ? <SecurityForm settings={settings} action="backup_reveal" label="Reveal backup codes" /> : null}
        {available("backup_generate") ? <SecurityForm settings={settings} action="backup_generate"
          label={settings.backupEnabled ? "Generate replacement codes" : "Generate backup codes"} /> : null}
        {available("backup_disable") ? <SecurityForm settings={settings} action="backup_disable" label="Disable backup codes">
          <label className="flex items-start gap-3 text-sm">
            <input type="checkbox" name="acknowledge" value="yes" required className="mt-1" />
            <span>I understand these codes will stop working. My authenticator stays enabled until removed separately.</span>
          </label>
        </SecurityForm> : null}
      </section>
    </div>
  );
}
