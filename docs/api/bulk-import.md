# Bulk product import (#80)

Staff can create many products and variants at once from a CSV or JSON file, either on the
`/admin/import` page or directly through GraphQL.

```graphql
mutation Import($content: String!) {
  importProducts(format: CSV, content: $content) {   # format: CSV | JSON
    success
    productsCreated
    variantsCreated
    brandsCreated
    errors { row field message }
  }
}
```

- **Roles:** `CATALOG_MANAGER` or `ADMIN` (`@PreAuthorize`), and the token also needs the
  `product:write` scope. Staff need AAL2 (an authenticator) unless the local AAL1 opt-in is enabled.
- **Limits:** content up to 1 MB (UTF-8). A CSV file can have at most 500 data rows; a JSON file
  can have at most 500 products and 500 variants in total. At most 200 errors are reported.
- **Creates only:** an import never updates or deletes anything. A product slug or variant SKU that
  already exists is reported as an error.

## Atomic policy

The whole file is parsed and validated before anything is written:

1. Format and structure: header or JSON shape, unknown fields, and the size and row limits.
2. Rules for each row (listed below).
3. Checks across the whole file: a SKU used twice in the file, or CSV rows with the same slug that
   give conflicting product-level values.
4. Database checks: slug or SKU already taken, and category slugs that don't exist.

If **any** error is found, nothing is imported. The response has `success: false` and an `errors`
list where each entry has:

- `row`: the 1-based CSV data row (not counting the header) or the 1-based JSON product index;
  `0` means the whole file or the header;
- `field`: the column, or a JSON path such as `variants[1].price`;
- `message`: what is wrong.

If the file is valid, all brands, products, category links and variants are created in a single
database transaction. If an unexpected failure happens partway through, the whole transaction is
rolled back.

Every import attempt, whether it succeeds or is rejected, writes one record to the
`admin_audit_log` with operation `importProducts`. The record holds the format, size in bytes,
created counts and error count, but not the file contents. You can view these records on
`/admin/audit`.

## Validation rules

| Field | Rule |
| --- | --- |
| slug | required; `^[a-z0-9]+(?:-[a-z0-9]+)*$`; at most 255 characters; must not already exist in the catalogue |
| name | required; at most 255 characters |
| description | optional; at most 10,000 characters |
| brand | optional; at most 100 characters. Matched to an existing brand by name ignoring case, or created ("get or create") |
| categories | optional; up to 20 existing category **slugs** |
| sku | required; at most 255 characters with no whitespace; must be unique in the file and in the catalogue |
| price | required; greater than 0, at most 2 decimals, at most 99,999,999.99 (EUR) |
| barcode | optional; at most 64 characters |
| weight | optional; whole grams from 1 to 1,000,000 |
| length / width / height | optional, but either all three or none; whole millimetres from 1 to 100,000 |
| active | optional; default true; accepts `true/false/yes/no/1/0` |
| attributes | optional; up to 50 entries; keys at most 100 characters, values at most 255 |

## CSV

The CSV uses RFC 4180 quoting and needs a header row. Each data row is **one variant**. Rows that
share a `slug` become one product. Later rows for the same slug can either leave the product-level
cells (`name`, `description`, `brand`, `categories`) blank or repeat exactly the same values; any
other value is reported as an error.

| Column | Meaning |
| --- | --- |
| `slug`*, `name`*, `description`, `brand` | product |
| `categories` | category slugs separated by `\|`, for example `perifeeria\|arvutid` |
| `sku`*, `price`*, `barcode` | variant |
| `weight_grams`, `length_mm`, `width_mm`, `height_mm` | variant shipping measures |
| `active` | variant active flag |
| `attr:<key>` | any number of attribute columns. A blank cell leaves the attribute out for that variant |

`*` marks a required column. Unknown columns are rejected.

Sample: [`samples/products-sample.csv`](samples/products-sample.csv).

## JSON

```json
{
  "products": [
    {
      "slug": "…", "name": "…", "description": "…", "brand": "…",
      "categories": ["category-slug"],
      "variants": [
        { "sku": "…", "price": "12.50", "attributes": {"color": "red"}, "barcode": "…",
          "weightGrams": 100, "lengthMm": 10, "widthMm": 10, "heightMm": 10, "active": true }
      ]
    }
  ]
}
```

- Each product needs at least one variant.
- `price` can be a string or a number. Use a string so the decimals are kept exactly.
- Unknown properties are rejected.

Sample: [`samples/products-sample.json`](samples/products-sample.json).

The sample files use the category slugs from the development seed data (`perifeeria`, `arvutid`,
`monitorid`, `mangurid`). In other environments, use slugs that exist in that catalogue.
