type Params = Record<string, string | string[] | undefined>;

/** Result of the last admin action, carried in ?notice= / ?error= and focused via #status. */
export function AdminStatus({ params }: { params: Params }) {
  const notice = typeof params.notice === "string" ? params.notice : null;
  const error = typeof params.error === "string" ? params.error : null;
  return (
    <div id="status" tabIndex={-1}>
      {notice ? <p role="status" className="account-badge" data-tone="ok">{notice}</p> : null}
      {error ? <p role="alert" className="account-badge" data-tone="off">{error}</p> : null}
    </div>
  );
}

export function AttributeRows({ values, extra = 2 }: { values: Record<string, unknown>; extra?: number }) {
  const rows = [...Object.entries(values).map(([key, value]) => [key, typeof value === "string" ? value : JSON.stringify(value)]),
    ...Array.from({ length: extra }, () => ["", ""])];
  return (
    <fieldset className="admin-attributes">
      <legend className="text-sm">Attributes (name and value; leave a row empty to remove it)</legend>
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
