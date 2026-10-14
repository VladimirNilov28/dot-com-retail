import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "About ByteCore",
  description: "ByteCore is dot-com retail, a student e-commerce project by ByteCore OÜ: our mission, team and links, and how our electronics store works.",
};

export default function AboutPage() {
  return (
    <article className="max-w-3xl space-y-6">
      <h1 className="text-2xl leading-tight font-semibold tracking-tight sm:text-3xl">About ByteCore</h1>
      <p className="text-lg text-muted">
        ByteCore is the storefront of <strong className="text-foreground">dot-com retail</strong>, a student
        e-commerce project run by ByteCore OÜ.
      </p>
      <section className="space-y-3" aria-labelledby="about-mission">
        <h2 id="about-mission" className="text-xl font-semibold tracking-tight">Our mission</h2>
        <p className="text-muted">
          Make buying computer hardware straightforward and trustworthy: honest prices and stock, clear product
          details, a fast checkout and careful handling of your account and payment data.
        </p>
      </section>
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
      <section className="space-y-3" aria-labelledby="about-team">
        <h2 id="about-team" className="text-xl font-semibold tracking-tight">Team</h2>
        <p className="text-muted">
          ByteCore is built and run by one person: <strong className="text-foreground">Vladimir Nilov</strong>,
          founder and full-stack developer, responsible for the storefront, backend services, payments and
          infrastructure.
        </p>
      </section>
      <section className="space-y-3" aria-labelledby="about-social">
        <h2 id="about-social" className="text-xl font-semibold tracking-tight">Find us online</h2>
        <ul className="space-y-2 text-muted">
          <li>
            <a href="https://github.com/VladimirNilov28" className="store-link" rel="noopener noreferrer" target="_blank">
              Vladimir Nilov on GitHub<span className="sr-only"> (opens in a new tab)</span>
            </a>
          </li>
          <li>
            <a
              href="https://github.com/VladimirNilov28/dot-com-retail"
              className="store-link"
              rel="noopener noreferrer"
              target="_blank"
            >
              dot-com retail source code on GitHub<span className="sr-only"> (opens in a new tab)</span>
            </a>
          </li>
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
