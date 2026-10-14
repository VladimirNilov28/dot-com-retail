import type { Metadata } from "next";
import Link from "next/link";
import { PackageSearch } from "lucide-react";
import { AdminDenied } from "@/components/admin-denied";
import { AdminEmpty, AdminPageHeader } from "@/components/admin-page-header";
import { AdminLoadError } from "@/components/admin-status";
import { OrderFilters, OrderList, Pagination } from "@/components/orders/order-view";
import { adminAccess } from "@/lib/admin/gate";
import { orderRoles } from "@/lib/admin/policy";
import { staffOrdersQuery, type OrderPage } from "@/lib/orders/documents";
import { orderVariables, parseOrderSearch, searchHref } from "@/lib/orders/model";
import { OrderApiError, orderGraphql } from "@/lib/orders/server";

export const metadata: Metadata = { title: "Admin · Orders", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

const base = "/admin/orders";

export default async function AdminOrders({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const search = parseOrderSearch(await searchParams, { staff: true });
  const access = await adminAccess(orderRoles);
  if (access.kind !== "ok") return <AdminDenied access={access} next={base} area="Order management" />;
  let result: OrderPage | null = null;
  let denied = false;
  try {
    result = (await orderGraphql<{ staffOrders: OrderPage }>(access.session.accessToken, staffOrdersQuery, orderVariables(search))).staffOrders;
  } catch (error) {
    denied = error instanceof OrderApiError && error.code === "PERMISSION_DENIED";
  }
  return (
    <div className="account-main">
      <AdminPageHeader title="Orders" description="Search, filter and fulfil customer and guest orders. Status changes, tracking, cancellations and refunds are audited." />
      <OrderFilters base={base} search={search} staff />
      {search.error ? <p role="alert" className="account-notice" data-tone="error">{search.error}</p> : null}
      {!result ? (
        <AdminLoadError>
          {denied ? "The order API denied this session. Sign out and in again so the order-management scope is granted." :
            "Orders could not be loaded. Retry shortly."}{" "}
          <Link prefetch={false} className="store-link" href={searchHref(base, search)}>Try again</Link>
        </AdminLoadError>
      ) : result.items.length === 0 ? (
        <div className="admin-panel"><AdminEmpty icon={<PackageSearch size={22} />} title="No orders match these filters" /></div>
      ) : <OrderList base={base} items={result.items} staff />}
      {result ? <Pagination base={base} search={search} totalCount={result.totalCount} size={result.size} /> : null}
    </div>
  );
}
