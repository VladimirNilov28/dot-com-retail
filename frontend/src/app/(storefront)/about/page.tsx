import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "About ByteCore",
  description: "ByteCore is dot-com retail, a student e-commerce project by ByteCore OÜ: an online electronics store with a real catalog, carts, secure checkout and accounts.",
};

export default function AboutPage() {
  return (
    <article className="max-w-3xl space-y-6">
      <h1 className="text-2xl leading-tight font-semibold tracking-tight sm:text-3xl">About ByteCore</h1>
      <p className="text-lg text-muted">
        ByteCore is the storefront of <strong className="text-foreground">dot-com retail</strong>, a student
        e-commerce project run by ByteCore OÜ.
      </p>
      <section className="space-y-3" aria-labelledby="about-what">
        <h2 id="about-what" className="text-xl font-semibold tracking-tight">What this shop is</h2>
        <p className="text-muted">
          We are building an online store for computers, components, peripherals and other electronics. The catalog,
          prices, stock levels, ratings, carts, accounts and orders you see here are real data in our own system —
          not mock-ups — so you can browse, compare and check out end to end.
        </p>
        <p className="text-muted">
          Because this is a learning project, payments run in Stripe&apos;s test mode and no real money is charged.
          Supported delivery destinations and shipping prices are always shown at checkout before you place an order.
        </p>
      </section>
      <section className="space-y-3" aria-labelledby="about-how">
        <h2 id="about-how" className="text-xl font-semibold tracking-tight">How we work</h2>
        <ul className="list-disc space-y-2 pl-5 text-muted">
          <li>Prices are shown in euros and confirmed again at checkout.</li>
          <li>Related-product suggestions come from shared categories and brands, not from tracking you.</li>
          <li>Your account is protected with modern sign-in, optional two-factor authentication and rate limiting.</li>
        </ul>
      </section>
      <section className="space-y-3" aria-labelledby="about-contact">
        <h2 id="about-contact" className="text-xl font-semibold tracking-tight">Get in touch</h2>
        <p className="text-muted">
          Questions about an order, a product or your account? Use our <Link href="/contact" className="store-link">contact form</Link> and
          we&apos;ll reply by email.
        </p>
        <div className="flex flex-wrap gap-3">
          <Link href="/catalog/all-products" className="store-cta">Browse the catalog</Link>
          <Link href="/contact" className="store-link inline-flex items-center py-2">Contact us</Link>
        </div>
      </section>
    </article>
  );
}
