import type { Metadata } from "next";
import { AdminDenied } from "@/components/admin-denied";
import { ChevronRight, Plus, Store, Trash2, Truck, Zap } from "lucide-react";
import { AdminPageHeader, AdminPanel } from "@/components/admin-page-header";
import { AdminLoadError, AdminStatus } from "@/components/admin-status";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { adminOnly } from "@/lib/admin/policy";
import { createDeliveryOption, deleteDeliveryOption, updateDeliveryOption } from "@/lib/admin/manage-actions";

export const metadata: Metadata = {
  title: "Admin · Delivery options",
  description: "Manage checkout delivery options: names, descriptions, EUR prices, estimated delivery days, display order and availability. Placed orders keep their snapshot.",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";

type Option = {
  id: string; code: string; name: string; description: string | null; price: string | number; currency: string;
  estimatedMinDays: number; estimatedMaxDays: number; active: boolean; sortOrder: number;
};

function Fields({ option }: { option?: Option }) {
  return (
    <div className="admin-form-grid">
      <label className="auth-field"><span className="text-sm">Name</span>
        <input className="auth-input" name="name" defaultValue={option?.name} required maxLength={100} /></label>
      <label className="auth-field admin-span-full"><span className="text-sm">Description shown at checkout</span>
        <input className="auth-input" name="description" defaultValue={option?.description ?? ""} maxLength={255} /></label>
      <label className="auth-field"><span className="text-sm">Price (EUR)</span>
        <input className="auth-input" name="price" defaultValue={option ? String(option.price) : ""} required inputMode="decimal" /></label>
      <label className="auth-field"><span className="text-sm">Minimum days</span>
        <input className="auth-input" name="estimatedMinDays" defaultValue={String(option?.estimatedMinDays ?? 1)} required inputMode="numeric" pattern="[0-9]*" /></label>
      <label className="auth-field"><span className="text-sm">Maximum days</span>
        <input className="auth-input" name="estimatedMaxDays" defaultValue={String(option?.estimatedMaxDays ?? 3)} required inputMode="numeric" pattern="[0-9]*" /></label>
      <label className="auth-field"><span className="text-sm">Sort order</span>
        <input className="auth-input" name="sortOrder" defaultValue={String(option?.sortOrder ?? 0)} required inputMode="numeric" pattern="[0-9]*" /></label>
      <label className="admin-check"><input type="checkbox" name="active" defaultChecked={option?.active ?? true} /> Offered at checkout</label>
    </div>
  );
}

export default async function AdminShipping({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const access = await adminAccess(adminOnly);
  if (access.kind !== "ok") return <AdminDenied access={access} next="/admin/shipping" area="Delivery options" />;
  let options: Option[] = [];
  let failed = false;
  try {
    options = (await adminGraphql<{ adminDeliveryOptions: Option[] }>(access.session.accessToken,
      `query { adminDeliveryOptions { id code name description price currency estimatedMinDays estimatedMaxDays active sortOrder } }`)).adminDeliveryOptions;
  } catch {
    failed = true;
  }
  const missing = ["STANDARD", "EXPRESS", "PICKUP"].filter((code) => !options.some((option) => option.code === code));
  const icon = (code: string) => code === "PICKUP" ? <Store size={18} /> : code === "EXPRESS" ? <Zap size={18} /> : <Truck size={18} />;
  return (
    <div className="account-main">
      <AdminPageHeader title="Delivery options"
        description="Changes apply to new checkout quotes immediately; shoppers with an open quote are asked to accept the new total. Placed orders keep their own shipping snapshot." />
      <AdminStatus params={params} />
      {failed ? <AdminLoadError>Delivery options could not be loaded. Retry shortly.</AdminLoadError> : null}
      {options.length ? (
        <ul className="admin-list" aria-label="Delivery options">
          {options.map((option) => (
            <li key={option.id}>
              <details className="admin-disclosure">
                <summary>
                  <span className="admin-thumb" aria-hidden="true">{icon(option.code)}</span>
                  <span className="grid min-w-0">
                    <span className="font-medium" id={`option-${option.id}`}>{option.name} <span className="admin-code">{option.code}</span></span>
                    <span className="admin-cell-sub">{money(option.price, option.currency)} · {option.estimatedMinDays}–{option.estimatedMaxDays} days</span>
                  </span>
                  <span className="account-badge" data-tone={option.active ? "ok" : "warn"}>{option.active ? "Active" : "Inactive"}</span>
                  <ChevronRight size={16} className="admin-chevron" aria-hidden="true" />
                </summary>
                <div className="admin-disclosure-body">
                  <form action={updateDeliveryOption} className="admin-form" aria-labelledby={`option-${option.id}`}>
                    <input type="hidden" name="optionId" value={option.id} />
                    <Fields option={option} />
                    <div className="admin-form-actions"><button className="store-cta" data-variant="primary" type="submit">Save {option.code}</button></div>
                  </form>
                  <form action={deleteDeliveryOption} className="admin-form-actions">
                    <input type="hidden" name="optionId" value={option.id} />
                    <label className="admin-check"><input type="checkbox" name="confirm" value="yes" required /> Confirm deleting {option.code}</label>
                    <button className="store-cta" data-variant="danger" data-size="sm" type="submit"><Trash2 size={14} aria-hidden="true" />Delete</button>
                  </form>
                </div>
              </details>
            </li>
          ))}
        </ul>
      ) : null}
      {!failed && missing.length ? (
        <AdminPanel id="new-option" title="New delivery option" description={`Available methods: ${missing.join(", ")}.`}>
          <form action={createDeliveryOption} className="admin-form">
            <label className="auth-field" style={{ maxWidth: "16rem" }}><span className="text-sm">Method</span>
              <select className="auth-input" name="code" required>{missing.map((code) => <option key={code} value={code}>{code}</option>)}</select></label>
            <Fields />
            <div className="admin-form-actions"><button className="store-cta" data-variant="primary" type="submit"><Plus size={16} aria-hidden="true" />Create</button></div>
          </form>
        </AdminPanel>
      ) : null}
    </div>
  );
}

function money(value: string | number, currency: string): string {
  const amount = Number(value);
  return Number.isFinite(amount) ? new Intl.NumberFormat("en-IE", { style: "currency", currency: currency || "EUR" }).format(amount) : `${value} ${currency}`;
}
