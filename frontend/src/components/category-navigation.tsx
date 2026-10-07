"use client";

import { Disclosure } from "@heroui/react";
import { ChevronDown } from "lucide-react";
import Link from "next/link";
import type { CSSProperties, MouseEvent } from "react";
import { catalogHref, categoryIndex, type Category } from "@/lib/catalog/model";

// The taxonomy is a flat list with parent links; guard the descent anyway so a
// future backend change can never hang the shell.
const MAX_DEPTH = 8;
// Columns are capped so a large taxonomy stays scannable instead of becoming a
// full-width sitemap. Fewer roots simply produce a narrower panel.
const MAX_COLUMNS = 4;
// Rows, not groups, decide the column count: ten childless roots belong in one
// column, not four nearly empty ones.
const ROWS_PER_COLUMN = 8;
// §9.1 defines a group as a root heading plus *its children*. Rendering every
// descendant turns a large taxonomy into the oversized sitemap the owner
// rejected, so the desktop panel stops at one level of children; deeper
// categories stay reachable from their parent's own category page and in full
// from the mobile drawer's collapsed disclosures.
const PANEL_CHILD_DEPTH = 1;

type CategoryNode = { category: Category; children: CategoryNode[] };

export function isPlainClick(event: MouseEvent) {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
}

function countRows(nodes: CategoryNode[], depth: number): number {
  return nodes.reduce((total, node) =>
    total + 1 + (depth < PANEL_CHILD_DEPTH ? countRows(node.children, depth + 1) : 0), 0);
}

function columnCount(tree: CategoryNode[]): number {
  const rows = countRows(tree, -1);
  return Math.max(1, Math.min(tree.length, MAX_COLUMNS, Math.ceil(rows / ROWS_PER_COLUMN)));
}

function categoryTree(categories: Category[]): CategoryNode[] {
  const index = categoryIndex(categories);
  function descend(parent: string | undefined, depth: number): CategoryNode[] {
    if (depth >= MAX_DEPTH) return [];
    return index.children(parent).map((category) => ({
      category,
      children: descend(category.id, depth + 1),
    }));
  }
  return descend(undefined, 0);
}

function contains(node: CategoryNode, pathname: string): boolean {
  return catalogHref(node.category.slug) === pathname ||
    node.children.some((child) => contains(child, pathname));
}

type LinkProps = { pathname: string; onNavigate: () => void };

function CategoryLink({
  category, className, pathname, onNavigate,
}: { category: Category; className: string } & LinkProps) {
  const href = catalogHref(category.slug);
  return (
    <Link
      href={href}
      prefetch={false}
      className={className}
      aria-current={pathname === href ? "page" : undefined}
      onClick={(event) => {
        if (isPlainClick(event)) onNavigate();
      }}
    >
      {category.name}
    </Link>
  );
}

function Subtree({ nodes, depth, limit, ...link }:
  { nodes: CategoryNode[]; depth: number; limit?: number } & LinkProps) {
  return (
    <ul className="store-category-subtree" data-depth={depth}>
      {nodes.map((node) => (
        <li key={node.category.id}>
          <CategoryLink category={node.category} className="store-category-link" {...link} />
          {node.children.length > 0 && (limit === undefined || depth < limit)
            ? <Subtree nodes={node.children} depth={depth + 1} limit={limit} {...link} />
            : null}
        </li>
      ))}
    </ul>
  );
}

function Indicator() {
  return (
    <Disclosure.Indicator>
      <ChevronDown className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
    </Disclosure.Indicator>
  );
}

/**
 * Grouped category navigation built from the real taxonomy already fetched for
 * the shell. `panel` is the wide desktop popover grid; `drawer` is the mobile
 * progressive-disclosure tree. The two share tree building, link semantics and
 * close-on-navigate; only the grouping affordance differs.
 */
export function CategoryNavigation({
  categories, variant, pathname, onNavigate,
}: { categories: Category[]; variant: "panel" | "drawer" } & LinkProps) {
  const tree = categoryTree(categories);
  if (tree.length === 0) return null;
  const link = { pathname, onNavigate };
  const panel = variant === "panel";

  return (
    <nav
      aria-label="Catalog categories"
      className={panel ? "store-category-panel" : "store-category-tree"}
    >
      {/* The column count drives the panel's own width, so a small taxonomy gets a
          small panel instead of an empty page-wide one. */}
      <ul
        className="store-category-groups"
        style={panel
          ? { "--store-category-columns": columnCount(tree) } as CSSProperties
          : undefined}
      >
        {tree.map((node) => (
          <li key={node.category.id} className="store-category-group">
            {panel ? (
              <>
                <h2 className="store-category-group-heading">
                  <CategoryLink category={node.category} className="store-category-root-link" {...link} />
                </h2>
                {node.children.length > 0
                  ? <Subtree nodes={node.children} depth={1} limit={PANEL_CHILD_DEPTH} {...link} />
                  : null}
              </>
            ) : node.children.length === 0 ? (
              <h2 className="store-category-row">
                <CategoryLink category={node.category} className="store-category-root-link" {...link} />
              </h2>
            ) : (
              <Disclosure defaultExpanded={contains(node, pathname)}>
                {/* The root link and the expand control stay separate, individually
                    operable controls so navigating never means expanding. */}
                <Disclosure.Heading level={2} className="store-category-row">
                  <CategoryLink category={node.category} className="store-category-root-link" {...link} />
                  <Disclosure.Trigger
                    className="store-category-toggle"
                    aria-label={`Subcategories of ${node.category.name}`}
                  >
                    <Indicator />
                  </Disclosure.Trigger>
                </Disclosure.Heading>
                <Disclosure.Content>
                  <Disclosure.Body className="store-category-disclosure-body">
                    <Subtree nodes={node.children} depth={1} {...link} />
                  </Disclosure.Body>
                </Disclosure.Content>
              </Disclosure>
            )}
          </li>
        ))}
      </ul>
    </nav>
  );
}
