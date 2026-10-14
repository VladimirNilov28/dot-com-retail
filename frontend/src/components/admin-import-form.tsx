"use client";

import { useActionState } from "react";
import { importProducts, type ImportState } from "@/lib/admin/manage-actions";

const initial: ImportState = { status: "idle", message: "", errors: [] };

export function AdminImportForm() {
  const [state, action, pending] = useActionState(importProducts, initial);
  return (
    <>
      <form action={action} className="account-card" encType="multipart/form-data">
        <label className="auth-field"><span className="text-sm">CSV or JSON file (up to 1 MB, 500 rows)</span>
          <input className="auth-input" type="file" name="file" accept=".csv,.json,text/csv,application/json" required /></label>
        <label className="auth-field"><span className="text-sm">Format</span>
          <select className="auth-input" name="format" defaultValue="">
            <option value="">Detect from file extension</option>
            <option value="CSV">CSV</option>
            <option value="JSON">JSON</option>
          </select></label>
        <button className="store-cta justify-self-start" type="submit" disabled={pending}>{pending ? "Importing…" : "Validate and import"}</button>
      </form>
      <div aria-live="polite">
        {state.status === "success" ? (
          <p role="status" className="account-badge" data-tone="ok">
            {state.fileName}: {state.message} {state.counts?.brands ? `${state.counts.brands} new brands created.` : ""}
          </p>
        ) : null}
        {state.status === "failed" ? <p role="alert" className="account-badge" data-tone="off">{state.fileName ? `${state.fileName}: ` : ""}{state.message}</p> : null}
      </div>
      {state.errors.length ? (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <caption className="text-sm text-muted">Problems found (row 0 means the whole file or the header)</caption>
            <thead><tr><th scope="col">Row</th><th scope="col">Field</th><th scope="col">Problem</th></tr></thead>
            <tbody>
              {state.errors.map((error, index) => (
                <tr key={index}><td>{error.row}</td><td>{error.field}</td><td>{error.message}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </>
  );
}
