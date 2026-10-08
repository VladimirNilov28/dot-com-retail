import type { Metadata } from "next";
import Link from "next/link";
import { AppWindow, Cpu, Gamepad2, Grid2X2, Headphones, Laptop, Monitor, Mouse, Network, Smartphone, type LucideIcon } from "lucide-react";
import { homeDiscoveryContent, HomeReadError } from "@/components/home-discovery";
import { categoryIndex, catalogHref } from "@/lib/catalog/model";
import { getCategories } from "@/lib/catalog/server";

export const metadata: Metadata = {
  title: "Browse electronics",
  description: "Explore electronics by category and browse real products, with clear prices and useful category shortcuts.",
};

const categoryIcons: Readonly<Record<string, LucideIcon>> = {
  computers: Laptop, arvutid: Laptop,
  laptops: Laptop, sulearvutid: Laptop,
  desktops: Monitor, lauaarvutid: Monitor,
  monitors: Monitor, monitorid: Monitor,
  components: Cpu, komponendid: Cpu,
  accessories: Mouse, peripherals: Mouse, perifeeria: Mouse,
  audio: Headphones,
  gaming: Gamepad2, mangurid: Gamepad2,
  smartphones: Smartphone, nutitelefonid: Smartphone,
  software: AppWindow, tarkvara: AppWindow,
  networking: Network, vorguseadmed: Network,
};

export default async function Home() {
  const result = await getCategories();
  const categories = result.status === "success" ? result.data.categories : [];
  const index = categoryIndex(categories);
  const roots = index.children();
  const discovery = result.status === "success" && roots.length
    ? await homeDiscoveryContent(categories) : null;
  return <div className="home-page">
    <header className="home-heading">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Find your next connection.</h1>
        <p className="text-muted">Explore electronics by category.</p>
      </div>
      <Link href="/catalog/all-products" className="store-link">All products</Link>
    </header>
    {result.status !== "success" ? <HomeReadError title="Categories unavailable" message={result.error.message} /> :
      roots.length ? <>
        <nav aria-label="Shop by category" className="home-categories">
          {roots.map((category) => {
            const children = index.children(category.id);
            const Icon = Object.hasOwn(categoryIcons, category.slug) ? categoryIcons[category.slug] : Grid2X2;
            return <section key={category.id} className="home-category">
              <h2 className="text-sm font-semibold tracking-tight sm:text-base">
                <Link href={catalogHref(category.slug)} prefetch={false} className="home-category-root">
                  <Icon className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
                  <span>{category.name}</span>
                </Link>
              </h2>
            </section>;
          })}
        </nav>
        {discovery}
      </> : <section className="space-y-2">
        <h2 className="text-xl font-semibold tracking-tight">No categories yet</h2>
        <p className="text-sm text-muted">You can still browse all available products or search the catalog.</p>
      </section>}
  </div>;
}
