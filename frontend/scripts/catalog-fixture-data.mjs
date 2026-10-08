import assert from "node:assert/strict";

export const fixtureSlugs = ["computers", "accessories", "monitors", "audio", "gaming", "laptops", "desktops", "keyboards", "components"];

export async function verifyFixture(endpoint) {
  const response = await fetch(endpoint, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query: "query FixturePreflight { categories { slug } searchProducts(input:{page:0,size:1,sort:PRICE_ASC}) { pageInfo { totalItems } } }" }),
  });
  assert.ok(response.ok, `FixturePreflight HTTP ${response.status}`);
  const result = await response.json();
  assert.ok(!result.errors, "FixturePreflight GraphQL failure");
  assert.deepEqual(result.data.categories.map(({ slug }) => slug).sort(), [...fixtureSlugs].sort(),
    "Expected isolated English-slug fixtures, not the owner's live taxonomy. Start catalog-fixtures.mjs.");
  assert.equal(result.data.searchProducts.pageInfo.totalItems, 30, "Expected the isolated thirty-product recipe");
}

export async function seedFixture(endpoint, token) {
  const url = new URL(endpoint);
  assert.equal(url.hostname, "127.0.0.1");
  assert.equal(url.port, "4063", "Fixture mutations are restricted to isolated Hive :4063");
  assert.equal(url.pathname, "/graphql");
  assert.ok(token, "A private setup credential is required");
  async function graphql(query, variables = {}) {
    const response = await fetch(endpoint, {
      method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ query, variables }),
    });
    assert.ok(response.ok, `Fixture setup HTTP ${response.status}`);
    const result = await response.json();
    assert.ok(!result.errors, `Fixture setup failed: ${JSON.stringify(result.errors)}`);
    return result.data;
  }
  const existing = await graphql("query FixtureEmpty { categories { id } searchProducts(input:{page:0,size:1,sort:PRICE_ASC}) { pageInfo { totalItems } } }");
  assert.equal(existing.categories.length, 0, "Refusing to seed a nonempty database");
  assert.equal(existing.searchProducts.pageInfo.totalItems, 0, "Refusing to overwrite existing products");
  const ids = {};
  for (const [name, slug, parent] of [
    ["Computers", "computers"], ["Accessories", "accessories"], ["Monitors", "monitors"],
    ["Audio", "audio"], ["Gaming", "gaming"], ["Laptops", "laptops", "computers"],
    ["Desktop PCs", "desktops", "computers"], ["Keyboards", "keyboards", "accessories"],
    ["Components", "components", "gaming"],
  ]) {
    const data = await graphql("mutation FixtureCategory($input:CreateCategoryInput!) { createCategory(input:$input) { id } }",
      { input: { name, slug, ...(parent ? { parentId: ids[parent] } : {}) } });
    ids[slug] = data.createCategory.id;
  }
  async function product(name, slug, category, prices, attributes = {}) {
    const data = await graphql("mutation FixtureProduct($input:CreateProductInput!) { createProduct(input:$input) { id } }",
      { input: { name, slug, ...(category ? { categoryIds: [ids[category]] } : {}) } });
    const variants = [];
    for (const [index, price] of prices.entries()) {
      const created = await graphql("mutation FixtureVariant($input:CreateProductVariantInput!) { createProductVariant(input:$input) { id } }",
        { input: { productId: data.createProduct.id, sku: `fixture-${slug}-${index}`, price, attributes } });
      variants.push(created.createProductVariant.id);
    }
    return variants;
  }
  const names = ["NovaBook 14", "Atlas 15 Creator Laptop", "Pulse 13 Everyday Laptop",
    "Vector 16 Workstation", "TerraBook Pro 14", "Arc 15 Performance Laptop"];
  for (let i = 0; i < 23; i++) {
    await product(names[i % 6] + (i >= 6 ? ` — Edition ${Math.floor(i / 6) + 1}` : ""),
      `laptop-${i + 1}`, "laptops",
      [`${499 + 35 * i}.00`, ...(i % 3 === 0 ? [`${649 + 35 * i}.00`] : [])],
      { ram: i % 2 === 0 ? "16GB" : "32GB", color: i % 3 === 0 ? "Silver" : "Black" });
  }
  await product("Nova Compact Desktop", "compact-desktop", "desktops", ["799.00"]);
  await product("QuietKey Mechanical Keyboard", "quietkey", "keyboards", ["79.90", "79.90"]);
  await product("Travel USB-C Adapter", "usb-c-adapter", "accessories", ["24.95"]);
  await product("Modular Computer Kit", "computer-kit", "computers", []);
  await product("Upcoming Portable Display", "portable-display", undefined, []);
  await product("Precision Game Controller", "game-controller", "components", ["49.95"]);
  const [retired] = await product("Retired Studio Laptop", "retired-laptop", undefined, ["9.99"]);
  await graphql("mutation FixtureRetire($id:ID!,$input:UpdateProductVariantInput!) { updateProductVariant(variantId:$id,input:$input) { id } }",
    { id: retired, input: { isActive: false } });
  await verifyFixture(endpoint);
}
