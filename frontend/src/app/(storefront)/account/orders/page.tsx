import type { Metadata } from "next";
import Link from "next/link";
import { AccountHeading } from "@/components/account/account-gate";
import { Notice } from "@/components/account/notice";
import { AuthProblem } from "@/components/auth-page";
import { OrderFilters, OrderList, Pagination } from "@/components/orders/order-view";
import { myOrdersQuery, type OrderPage } from "@/lib/orders/documents";
import { orderVariables, parseOrderSearch, searchHref } from "@/lib/orders/model";
import { customerSession, orderGraphql } from "@/lib/orders/server";

export const metadata: Metadata = { title: "Orders", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

const base = "/account/orders";

export default async function AccountOrders({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const search = parseOrderSearch(await searchParams);
  const session = await customerSession();
  if (session.kind === "anonymous") return (
    <div className="account-main">
      <AccountHeading title="Your orders" description="Sign in to see your order history." />
      { }
      <a className="store-cta justify-self-start" href={`/login?returnTo=${encodeURIComponent(base)}`}>Sign in</a>
    </div>
  );
  if (session.kind === "problem") return <AuthProblem code={session.code} returnTo={base} />;
  let result: OrderPage | null = null;
  try {
    result = (await orderGraphql<{ myOrders: OrderPage }>(session.accessToken, myOrdersQuery, orderVariables(search))).myOrders;
  } catch (error) {
    console.error("order list failed", error instanceof Error ? error.name : "unknown");
  }
  const filtered = Boolean(search.status || search.from || search.to);
  return (
    <div className="account-main">
      <AccountHeading title="Your orders" description="Track deliveries, cancel eligible orders and request refunds." />
      <OrderFilters base={base} search={search} />
      {search.error ? <Notice tone="error">{search.error}</Notice> : null}
      {!result ? (
        <div role="alert" className="account-card">
          <p>Your orders could not be loaded right now.</p>
          <Link prefetch={false} className="store-link" href={searchHref(base, search)}>Try again</Link>
        </div>
      ) : result.items.length === 0 ? (
        <div className="account-card">
          <p>{filtered ? "No orders match these filters." : search.page > 1 ? "There are no orders on this page." : "You haven't placed any orders yet."}</p>
          {filtered || search.page > 1 ? <Link prefetch={false} className="store-link" href={base}>Show all orders</Link> :
            <Link prefetch={false} className="store-link" href="/catalog/all-products">Start shopping</Link>}
        </div>
      ) : <OrderList base={base} items={result.items} />}
      {result ? <Pagination base={base} search={search} totalCount={result.totalCount} size={result.size} /> : null}
    </div>
  );
}
