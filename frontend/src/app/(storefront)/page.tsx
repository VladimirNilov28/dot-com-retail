import Link from "next/link";

const availability = [
  {
    area: "Catalog",
    status: "Product browsing is being built.",
  },
  {
    area: "Search",
    status: "Search is not available yet.",
  },
  {
    area: "Account and cart",
    status: "Sign-in, cart and checkout are not available yet.",
  },
];

export default function Home() {
  return (
    <div className="grid gap-10 lg:grid-cols-2 lg:items-start lg:gap-16">
      <section className="max-w-xl space-y-4">
        <p className="text-sm font-semibold tracking-widest text-accent uppercase">
          ByteCore
        </p>
        <h1 className="text-3xl leading-tight font-semibold tracking-tight sm:text-4xl">
          Electronics, clearly connected.
        </h1>
        <p className="text-lg text-muted">
          The storefront is being built one part at a time. Everything shown
          here is real; nothing is simulated while a feature is still missing.
        </p>
        <Link href="/catalog" className="store-cta mt-2">
          Browse the catalog
        </Link>
      </section>

      <section aria-labelledby="availability-heading" className="max-w-xl">
        <h2
          id="availability-heading"
          className="text-sm font-semibold tracking-widest text-muted uppercase"
        >
          Available now
        </h2>
        <dl className="mt-4 divide-y divide-separator border-t border-separator text-sm">
          {availability.map((entry) => (
            <div
              key={entry.area}
              className="grid gap-1 py-3 sm:grid-cols-[10rem_minmax(0,1fr)] sm:gap-4"
            >
              <dt className="font-medium text-foreground">{entry.area}</dt>
              <dd className="text-muted">{entry.status}</dd>
            </div>
          ))}
        </dl>
      </section>
    </div>
  );
}
