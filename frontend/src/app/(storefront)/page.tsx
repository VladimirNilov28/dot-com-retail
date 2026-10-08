import type { Metadata } from "next";
import Link from "next/link";
import { homeDiscoveryContent, HomeReadError } from "@/components/home-discovery";
import { categoryIndex, catalogHref } from "@/lib/catalog/model";
import { getCategories } from "@/lib/catalog/server";

export const metadata: Metadata = {
  title: "Browse electronics",
  description: "Explore electronics by category and browse real products, with clear prices and useful category shortcuts.",
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
            return <section key={category.id} className="home-category">
              <h2 className="text-base font-semibold tracking-tight">
                <Link href={catalogHref(category.slug)} prefetch={false} className="home-category-link">{category.name}</Link>
              </h2>
              {children.length > 0 && <ul className="home-subcategories">
                {children.slice(0, 4).map((child) => <li key={child.id}>
                  <Link href={catalogHref(child.slug)} prefetch={false} className="home-category-link">{child.name}</Link>
                </li>)}
                {children.length > 4 && <li>
                  <Link href={catalogHref(category.slug)} prefetch={false} className="home-category-link">All {category.name} categories</Link>
                </li>}
              </ul>}
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
