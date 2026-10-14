import { CircleAlert, CircleCheck, Info } from "lucide-react";

const icons = { success: CircleCheck, error: CircleAlert, info: Info };

export function Notice({ tone, children }: { tone: "success" | "error" | "info"; children: React.ReactNode }) {
  const Icon = icons[tone];
  return (
    <p role={tone === "error" ? "alert" : "status"} className="admin-alert" data-tone={tone === "error" ? "danger" : tone}>
      <Icon size={18} aria-hidden="true" /><span>{children}</span>
    </p>
  );
}

type Query = Record<string, string | string[] | undefined>;
type Message = { tone: "success" | "error" | "info"; text: string };

const messages: Record<string, Record<string, Message>> = {
  saved: {
    password: { tone: "success", text: "Your password has been saved. Other signed-in sessions were signed out." },
    totp_confirm: { tone: "success", text: "Authenticator app enabled." },
    totp_disable: { tone: "success", text: "Authenticator app removed. Recovery codes were turned off with it." },
    backup_generate: { tone: "info", text: "New recovery codes are ready. Save them, then activate them below." },
    backup_confirm: { tone: "success", text: "Recovery codes activated. Any previous codes no longer work." },
    backup_disable: { tone: "success", text: "Recovery codes turned off." },
    google_connect: { tone: "success", text: "Google is now connected to this account." },
    google_disconnect: { tone: "success", text: "Google has been disconnected." },
    "1": { tone: "success", text: "Your settings have been updated." },
  },
  notice: {
    last_method: { tone: "error", text: "Add a password before disconnecting Google, so you can still sign in." },
    totp_required: { tone: "error", text: "Set up an authenticator app before creating recovery codes." },
    password_mismatch: { tone: "error", text: "The passwords don't match." },
    minimum: { tone: "error", text: "Use a password of at least 8 bytes." },
    maximum: { tone: "error", text: "Use a password of at most 72 bytes." },
    recovery_mode: { tone: "error", text: "You signed in with a recovery code. Replace your authenticator before changing other settings." },
  },
  stepup: {
    sent: { tone: "info", text: "We emailed you a 6-digit code. It expires in 10 minutes." },
    verified: { tone: "success", text: "Verified. You can make changes for the next 10 minutes." },
    invalid: { tone: "error", text: "That code is not correct." },
    expired: { tone: "error", text: "That code has expired or was already used. Request a new one." },
    limited: { tone: "error", text: "Too many attempts. Wait a few minutes, then request a new code." },
    unavailable: { tone: "error", text: "We couldn't send the code. Try again shortly." },
    required: { tone: "error", text: "Verify it's you before making this change." },
  },
  email: {
    sent: { tone: "info", text: "We sent a code to the new address. Enter it below to finish." },
    changed: { tone: "success", text: "Your email address has been changed. We notified your previous address." },
    invalid: { tone: "error", text: "Enter a valid email address." },
    same: { tone: "error", text: "That is already your email address." },
    conflict: { tone: "error", text: "That email address can't be used. Choose another address." },
    expired: { tone: "error", text: "That code has expired or was already used. Start the change again." },
    limited: { tone: "error", text: "Too many attempts. Try again later." },
    unavailable: { tone: "error", text: "We couldn't complete the change. No changes were made." },
    cancelled: { tone: "info", text: "The email change was cancelled." },
    recovery_mode: { tone: "error", text: "Replace your authenticator before changing your email address." },
  },
  sessions: {
    revoked: { tone: "success", text: "That session has been signed out." },
    revoked_all: { tone: "success", text: "All other sessions have been signed out." },
    missing: { tone: "info", text: "That session had already ended." },
  },
  google: {
    cancelled: { tone: "info", text: "Google connection was cancelled. Nothing was changed." },
    failed: { tone: "error", text: "Google could not be connected. A Google account already used by another account can't be moved here." },
  },
  invalid: { "1": { tone: "error", text: "The change was not accepted. Check your details and try again." } },
  recovery: { "1": { tone: "info", text: "You signed in with a recovery code. Other sessions were signed out and we emailed you. Replace your authenticator now." } },
};

export function QueryNotices({ query, keys }: { query: Query; keys: (keyof typeof messages)[] }) {
  const found = keys.flatMap((key) => {
    const value = query[key];
    const message = typeof value === "string" ? messages[key][value] : undefined;
    return message ? [{ key, message }] : [];
  });
  return found.map(({ key, message }) => <Notice key={key} tone={message.tone}>{message.text}</Notice>);
}
