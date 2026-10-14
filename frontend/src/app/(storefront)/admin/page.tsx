import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { AdminDenied } from "@/components/admin-denied";
import { adminAccess } from "@/lib/admin/gate";
import { adminRoles, orderRoles, staffRoles } from "@/lib/admin/policy";

export const metadata: Metadata = { title: "Admin", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

/** Sends staff to the first admin section their role can use. */
export default async function AdminHome() {
  const access = await adminAccess(staffRoles);
  if (access.kind !== "ok") return <AdminDenied access={access} next="/admin" area="The admin console" />;
  const { role } = access.session;
  redirect(adminRoles.has(role) ? "/admin/products" : orderRoles.has(role) ? "/admin/orders" : "/admin/security");
}
