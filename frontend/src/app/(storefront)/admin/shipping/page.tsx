import type { Metadata } from "next";
import { AdminDenied } from "@/components/admin-denied";
import { AdminStatus } from "@/components/admin-status";
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
    <>
      <label className="auth-field"><span className="text-sm">Name</span>
        <input className="auth-input" name="name" defaultValue={option?.name} required maxLength={100} /></label>
      <label className="auth-field"><span className="text-sm">Description shown at checkout</span>
        <input className="auth-input" name="description" defaultValue={option?.description ?? ""} maxLength={255} /></label>
      <label className="auth-field"><span className="text-sm">Price (EUR)</span>
        <input className="auth-input" name="price" defaultValue={option ? String(option.price) : ""} required inputMode="decimal" /></label>
      <label className="auth-field"><span className="text-sm">Minimum days</span>
        <input className="auth-input" name="estimatedMinDays" defaultValue={String(option?.estimatedMinDays ?? 1)} required inputMode="numeric" pattern="[0-9]*" /></label>
      <label className="auth-field"><span className="text-sm">Maximum days</span>
        <input className="auth-input" name="estimatedMaxDays" defaultValue={String(option?.estimatedMaxDays ?? 3)} required inputMode="numeric" pattern="[0-9]*" /></label>
      <label className="auth-field"><span className="text-sm">Sort order</span>
        <input className="auth-input" name="sortOrder" defaultValue={String(option?.sortOrder ?? 0)} required inputMode="numeric" pattern="[0-9]*" /></label>
      <label className="admin-checks"><input type="checkbox" name="active" defaultChecked={option?.active ?? true} /> Offered at checkout</label>
    </>
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
  return (
    <div className="account-main">
      <h1 className="text-2xl font-semibold tracking-tight">Delivery options</h1>
      <AdminStatus params={params} />
      <p className="text-sm text-muted">Changes apply to new checkout quotes immediately; shoppers with an open quote are asked to accept the new total. Placed orders keep their own shipping snapshot.</p>
      {failed ? <p role="alert" className="text-muted">Delivery options could not be loaded. Retry shortly.</p> : null}
      {options.map((option) => (
        <section key={option.id} className="account-card" aria-labelledby={`option-${option.id}`}>
          <h2 id={`option-${option.id}`} className="text-lg font-semibold">{option.code} <span className="account-badge" data-tone={option.active ? "ok" : "off"}>{option.active ? "Active" : "Inactive"}</span></h2>
          <form action={updateDeliveryOption} className="admin-inline">
            <input type="hidden" name="optionId" value={option.id} />
            <Fields option={option} />
            <button className="store-cta" type="submit">Save {option.code}</button>
          </form>
          <form action={deleteDeliveryOption} className="admin-inline">
            <input type="hidden" name="optionId" value={option.id} />
            <label className="admin-checks"><input type="checkbox" name="confirm" value="yes" required /> Confirm deleting {option.code}</label>
            <button className="store-cta" type="submit">Delete</button>
          </form>
        </section>
      ))}
      {!failed && missing.length ? (
        <section className="account-card" aria-labelledby="new-option">
          <h2 id="new-option" className="text-lg font-semibold">New delivery option</h2>
          <form action={createDeliveryOption} className="admin-inline">
            <label className="auth-field"><span className="text-sm">Method</span>
              <select className="auth-input" name="code" required>{missing.map((code) => <option key={code} value={code}>{code}</option>)}</select></label>
            <Fields />
            <button className="store-cta" type="submit">Create</button>
          </form>
        </section>
      ) : null}
    </div>
  );
}
