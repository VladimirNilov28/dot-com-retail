import Link from "next/link";
import { ChevronLeft } from "lucide-react";

export function AdminPageHeader({ title, description, back, actions, meta }: {
  title: React.ReactNode; description?: React.ReactNode; back?: { href: string; label: string };
  actions?: React.ReactNode; meta?: React.ReactNode;
}) {
  return (
    <header className="admin-page-header">
      <div className="admin-page-heading">
        {back ? (
          <Link prefetch={false} href={back.href} className="admin-back"><ChevronLeft size={16} aria-hidden="true" />{back.label}</Link>
        ) : null}
        <h1>{title}</h1>
        {description ? <p className="admin-page-description">{description}</p> : null}
        {meta ? <div className="admin-page-meta">{meta}</div> : null}
      </div>
      {actions ? <div className="admin-page-actions">{actions}</div> : null}
    </header>
  );
}

/** A titled panel. `tone="danger"` marks destructive actions. */
export function AdminPanel({ id, title, description, actions, tone, children }: {
  id: string; title: React.ReactNode; description?: React.ReactNode; actions?: React.ReactNode;
  tone?: "danger"; children: React.ReactNode;
}) {
  return (
    <section className="admin-panel" data-tone={tone} aria-labelledby={`${id}-heading`} id={id}>
      <div className="admin-panel-head">
        <div className="min-w-0">
          <h2 id={`${id}-heading`}>{title}</h2>
          {description ? <p className="admin-panel-description">{description}</p> : null}
        </div>
        {actions ? <div className="admin-panel-actions">{actions}</div> : null}
      </div>
      {children}
    </section>
  );
}

export function AdminEmpty({ icon, title, children }: { icon?: React.ReactNode; title: string; children?: React.ReactNode }) {
  return (
    <div className="admin-empty">
      {icon ? <span className="admin-empty-icon" aria-hidden="true">{icon}</span> : null}
      <p className="font-medium">{title}</p>
      {children ? <div className="text-sm text-muted">{children}</div> : null}
    </div>
  );
}
