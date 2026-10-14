import type { Metadata } from "next";
import { ContactForm } from "@/components/contact/contact-form";

export const metadata: Metadata = {
  title: "Contact us",
  description: "Contact ByteCore about orders, payments, products or your account. Send a message using our contact form and our support team will reply to you by email.",
};

export default function ContactPage() {
  return (
    <div className="max-w-2xl space-y-6">
      <header className="space-y-2">
        <h1 className="text-2xl leading-tight font-semibold tracking-tight sm:text-3xl">Contact us</h1>
        <p className="text-muted">
          Send us a message and we&apos;ll reply by email. For questions about an order, include your order reference.
        </p>
      </header>
      <ContactForm />
    </div>
  );
}
