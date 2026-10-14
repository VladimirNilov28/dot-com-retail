import { CircleAlert, CircleCheck } from "lucide-react";

type Params = Record<string, string | string[] | undefined>;

/** Result of the last admin action, carried in ?notice= / ?error= and focused via #status. */
export function AdminStatus({ params }: { params: Params }) {
  const notice = typeof params.notice === "string" ? params.notice.slice(0, 300) : null;
  const error = typeof params.error === "string" ? params.error.slice(0, 300) : null;
  return (
    <div id="status" tabIndex={-1} className="admin-status">
      {notice ? <p role="status" className="admin-alert" data-tone="success"><CircleCheck size={18} aria-hidden="true" />{notice}</p> : null}
      {error ? <p role="alert" className="admin-alert" data-tone="danger"><CircleAlert size={18} aria-hidden="true" />{error}</p> : null}
    </div>
  );
}

/** Inline load failure for a panel or page. */
export function AdminLoadError({ children }: { children: React.ReactNode }) {
  return <p role="alert" className="admin-alert" data-tone="danger"><CircleAlert size={18} aria-hidden="true" />{children}</p>;
}

export function AttributeRows({ values, extra = 2 }: { values: Record<string, unknown>; extra?: number }) {
  const rows = [...Object.entries(values).map(([key, value]) => [key, typeof value === "string" ? value : JSON.stringify(value)]),
    ...Array.from({ length: extra }, () => ["", ""])];
  return (
    <fieldset className="admin-attributes">
      <legend className="admin-legend">Attributes <span className="text-muted font-normal">· name and value; leave a row empty to remove it</span></legend>
      {rows.map(([key, value], index) => (
        <div key={index} className="admin-inline">
          <label className="auth-field"><span className="text-sm">Attribute {index + 1} name</span>
            <input className="auth-input" name={`attr-key-${index}`} defaultValue={key} maxLength={100} /></label>
          <label className="auth-field"><span className="text-sm">Attribute {index + 1} value</span>
            <input className="auth-input" name={`attr-value-${index}`} defaultValue={value} maxLength={255} /></label>
        </div>
      ))}
    </fieldset>
  );
}
