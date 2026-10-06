export function CatalogSkeleton() {
  return (
    <div aria-busy="true" className="catalog-loading">
      <div className="catalog-toolbar"><p role="status" className="text-sm text-muted">Loading products…</p>
        <div className="catalog-skeleton h-10 w-24" aria-hidden="true" /></div>
      <div className="catalog-products" aria-hidden="true">
        {Array.from({ length: 20 }, (_, index) => (
          <div className="product-card" key={index}>
            <div className="catalog-image catalog-skeleton" />
            <div className="product-card-copy space-y-3">
              <div className="catalog-skeleton h-10" />
              <div className="catalog-skeleton h-6 w-2/3" />
              <div className="catalog-skeleton h-5 w-3/4" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
