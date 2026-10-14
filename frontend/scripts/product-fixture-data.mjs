import assert from "node:assert/strict";
import { verifyFixture } from "./catalog-fixture-data.mjs";

export async function seedProductFixture(endpoint, token) {
  const url = new URL(endpoint);
  assert.equal(url.origin, "http://127.0.0.1:4063", "Product setup is restricted to disposable Hive");
  assert.equal(url.pathname, "/graphql");
  assert.ok(token, "Private supported setup credentials are required");
  await verifyFixture(endpoint);
  async function graphql(query, variables) {
    const response = await fetch(endpoint, {
      method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ query, variables }),
    });
    assert.ok(response.ok, `Product fixture HTTP ${response.status}`);
    const result = await response.json();
    assert.ok(!result.errors, "Supported product fixture operation failed");
    return result.data;
  }
  const { product } = await graphql("query { product(slug:\"laptop-1\") { id variants { id } } }");
  assert.equal(product.variants.length, 2);
  await graphql("mutation($id:ID!,$input:UpdateProductInput!){updateProduct(productId:$id,input:$input){id}}", {
    id: product.id, input: { description: "A portable computer for everyday work.\nCompare the actual storage options and specifications before choosing a variant." },
  });
  for (const [index, variant] of product.variants.entries()) {
    await graphql("mutation($id:ID!,$input:UpdateProductVariantInput!){updateProductVariant(variantId:$id,input:$input){id}}", {
      id: variant.id, input: { attributes: { ram: "16GB", color: "Silver", storage: index ? "1TB" : "512GB",
        ports: ["USB-C", "HDMI"], backlight: true }, weightGrams: index ? 1500 : 1400 },
    });
  }
  const removed = await graphql("mutation($input:CreateProductVariantInput!){createProductVariant(input:$input){id}}", {
    input: { productId: product.id, sku: "fixture-removed-variant", price: "700.00", attributes: { storage: "2TB" } },
  });
  await graphql("mutation($id:ID!){deleteProductVariant(variantId:$id)}", { id: removed.createProductVariant.id });
  await verifyFixture(endpoint);
  return { removedVariantId: removed.createProductVariant.id };
}
