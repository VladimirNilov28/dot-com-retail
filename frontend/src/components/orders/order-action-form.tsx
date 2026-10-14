"use client";

import { Button } from "@heroui/react";
import { useId, useRef, useState, type ReactNode } from "react";

/** Server-action form with a double-submit guard and an optional native confirmation dialog. */
export function OrderActionForm({ action, fields, label, pendingLabel = "Saving…", variant = "primary", confirm, className = "account-form", children }: {
  action: (form: FormData) => Promise<void>;
  fields: Record<string, string>;
  label: string;
  pendingLabel?: string;
  variant?: "primary" | "secondary" | "danger";
  confirm?: { title: string; body: string; action: string; dismiss?: string };
  className?: string;
  children?: ReactNode;
}) {
  const [pending, setPending] = useState(false);
  const submitting = useRef(false);
  const form = useRef<HTMLFormElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  return (
    <form ref={form} action={action} className={className} aria-busy={pending} onSubmit={(event) => {
      if (submitting.current) { event.preventDefault(); return; }
      if (confirm && !dialog.current?.dataset.confirmed) {
        event.preventDefault();
        dialog.current?.showModal();
        return;
      }
      submitting.current = true;
      setPending(true);
    }}>
      {Object.entries(fields).map(([name, value]) => <input key={name} type="hidden" name={name} value={value} />)}
      {children}
      <div>
        <Button type="submit" variant={variant} isDisabled={pending}>{pending ? pendingLabel : label}</Button>
      </div>
      {confirm ? (
        <dialog ref={dialog} className="account-dialog" aria-labelledby={titleId}>
          <h2 id={titleId} className="text-lg font-semibold">{confirm.title}</h2>
          <p className="text-sm text-muted">{confirm.body}</p>
          <div className="account-dialog-actions">
            <Button variant="secondary" onPress={() => dialog.current?.close()}>{confirm.dismiss ?? "Go back"}</Button>
            <Button variant="danger" onPress={() => {
              if (!dialog.current || !form.current) return;
              dialog.current.dataset.confirmed = "yes";
              dialog.current.close();
              form.current.requestSubmit();
            }}>{confirm.action}</Button>
          </div>
        </dialog>
      ) : null}
    </form>
  );
}
