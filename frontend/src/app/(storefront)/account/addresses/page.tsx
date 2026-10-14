import type { Metadata } from "next";
import { AccountHeading, accountContext, type AccountQuery } from "@/components/account/account-gate";
import { AddressBook } from "@/components/account/address-book";

export const metadata: Metadata = { title: "Saved addresses", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function AddressesPage({ searchParams }: { searchParams: Promise<AccountQuery> }) {
  const title = "Saved addresses";
  const context = await accountContext("/account/addresses", title, searchParams);
  if ("element" in context) return context.element;
  return (
    <div className="account-main">
      <AccountHeading title={title} description="Save delivery addresses to fill in checkout faster. You can pick any saved address when you place an order." />
      <AddressBook />
    </div>
  );
}
