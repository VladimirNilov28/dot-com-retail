"use client";

import { useActionState } from "react";
import { CircleAlert, CircleCheck, FileUp } from "lucide-react";
import { importProducts, type ImportState } from "@/lib/admin/manage-actions";

const initial: ImportState = { status: "idle", message: "", errors: [] };

export function AdminImportForm() {
  const [state, action, pending] = useActionState(importProducts, initial);
  return (
    <div className="grid gap-4 content-start min-w-0">
      <form action={action} className="admin-panel" encType="multipart/form-data" aria-labelledby="upload-heading">
        <div className="admin-panel-head"><div>
          <h2 id="upload-heading">Upload file</h2>
          <p className="admin-panel-description">CSV or JSON, up to 1 MB and 500 rows.</p>
        </div></div>
        <div className="admin-dropzone">
          <span className="admin-empty-icon" aria-hidden="true"><FileUp size={20} /></span>
          <label className="auth-field"><span className="text-sm">CSV or JSON file</span>
            <input className="auth-input" type="file" name="file" accept=".csv,.json,text/csv,application/json" required /></label>
        </div>
        <label className="auth-field"><span className="text-sm">Format</span>
          <select className="auth-input" name="format" defaultValue="">
            <option value="">Detect from file extension</option>
            <option value="CSV">CSV</option>
            <option value="JSON">JSON</option>
          </select></label>
        <div className="admin-form-actions">
          <button className="store-cta" data-variant="primary" type="submit" disabled={pending}>{pending ? "Importing…" : "Validate and import"}</button>
        </div>
      </form>
      <div aria-live="polite" className="admin-status">
        {state.status === "success" ? (
          <p role="status" className="admin-alert" data-tone="success"><CircleCheck size={18} aria-hidden="true" />
            <span>{state.fileName}: {state.message} {state.counts?.brands ? `${state.counts.brands} new brands created.` : ""}</span>
          </p>
        ) : null}
        {state.status === "failed" ? <p role="alert" className="admin-alert" data-tone="danger"><CircleAlert size={18} aria-hidden="true" />
          <span>{state.fileName ? `${state.fileName}: ` : ""}{state.message}</span></p> : null}
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
    </div>
  );
}
