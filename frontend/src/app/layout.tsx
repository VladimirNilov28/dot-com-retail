import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "ByteCore",
    template: "%s | ByteCore",
  },
  description: "The ByteCore storefront. Shopping features are coming soon.",
};

export const viewport: Viewport = {
  colorScheme: "dark",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      dir="ltr"
      className="dark h-full antialiased"
      data-theme="dark"
    >
      <body className="min-h-full bg-background text-foreground">
        <a
          href="#main-content"
          className="fixed top-4 left-4 z-50 -translate-y-24 rounded-lg bg-accent px-4 py-2 text-accent-foreground focus:translate-y-0 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-focus"
        >
          Skip to content
        </a>
        {children}
      </body>
    </html>
  );
}
