package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.repositories.category.CategoryRepository;
import ee.bytecore.backend.repositories.product.BrandRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Atomic bulk product import (see docs/api/bulk-import.md). Every row is validated first;
 * if anything is invalid nothing is written and per-row errors are returned. Otherwise all
 * products, brands and variants are created through the regular catalog services in the
 * caller's single transaction.
 */
@Service
public class ProductImportService {
    public static final int MAX_CONTENT_BYTES = 1_048_576;
    public static final int MAX_ROWS = 500;
    public static final int MAX_ERRORS = 200;
    static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
    private static final int MAX_DESCRIPTION = 10_000;
    private static final int MAX_CATEGORIES = 20;
    private static final int MAX_ATTRIBUTES = 50;
    private static final Set<String> CSV_COLUMNS = Set.of(
            "slug",
            "name",
            "description",
            "brand",
            "categories",
            "sku",
            "price",
            "barcode",
            "weight_grams",
            "length_mm",
            "width_mm",
            "height_mm",
            "active");
    private static final Set<String> JSON_PRODUCT_FIELDS =
            Set.of("slug", "name", "description", "brand", "categories", "variants");
    private static final Set<String> JSON_VARIANT_FIELDS =
            Set.of("sku", "price", "attributes", "barcode", "weightGrams", "lengthMm", "widthMm", "heightMm", "active");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    public enum Format {
        CSV,
        JSON
    }

    public record RowError(int row, String field, String message) {}

    public record Result(
            boolean success, int productsCreated, int variantsCreated, int brandsCreated, List<RowError> errors) {
        static Result failed(List<RowError> errors) {
            return new Result(false, 0, 0, 0, List.copyOf(errors));
        }
    }

    private static final class ProductDraft {
        final int row;
        String slug;
        String name;
        String description;
        String brand;
        List<String> categories = List.of();
        final List<VariantDraft> variants = new ArrayList<>();

        ProductDraft(int row) {
            this.row = row;
        }
    }

    private record VariantDraft(
            int row,
            String prefix,
            String sku,
            BigDecimal price,
            Map<String, Object> attributes,
            String barcode,
            Integer weightGrams,
            Integer lengthMm,
            Integer widthMm,
            Integer heightMm,
            boolean active) {}

    private static final class Errors {
        final List<RowError> list = new ArrayList<>();

        void add(int row, String field, String message) {
            if (list.size() < MAX_ERRORS) list.add(new RowError(row, field, message));
        }

        boolean any() {
            return !list.isEmpty();
        }
    }

    private final ProductService productService;
    private final ProductVariantService variantService;
    private final BrandService brandService;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;

    public ProductImportService(
            ProductService productService,
            ProductVariantService variantService,
            BrandService brandService,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            CategoryRepository categoryRepository,
            BrandRepository brandRepository) {
        this.productService = productService;
        this.variantService = variantService;
        this.brandService = brandService;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
    }

    @Transactional
    public Result importProducts(Format format, String content) {
        if (format == null) throw new IllegalArgumentException("Import format is required");
        if (content == null || content.isBlank())
            return Result.failed(List.of(new RowError(0, "file", "File is empty")));
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES)
            return Result.failed(List.of(new RowError(0, "file", "File is larger than 1 MB")));
        Errors errors = new Errors();
        List<ProductDraft> drafts = format == Format.CSV ? parseCsv(content, errors) : parseJson(content, errors);
        if (errors.any()) return Result.failed(errors.list);
        Map<String, Long> categoryIds = validateAgainstDatabase(drafts, errors);
        if (errors.any()) return Result.failed(errors.list);
        return create(drafts, categoryIds);
    }

    // ---- CSV -------------------------------------------------------------------------------

    private List<ProductDraft> parseCsv(String content, Errors errors) {
        List<List<String>> records;
        try {
            records = CsvReader.parse(content);
        } catch (CsvReader.CsvFormatException e) {
            errors.add(e.record(), "file", e.getMessage());
            return List.of();
        }
        if (records.isEmpty()) {
            errors.add(0, "file", "File is empty");
            return List.of();
        }
        List<String> header = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : records.getFirst()) {
            String column = raw.trim();
            String key = column.toLowerCase(Locale.ROOT);
            if (key.startsWith("attr:")) {
                String attribute = column.substring(5).trim();
                if (attribute.isEmpty() || attribute.length() > 100)
                    errors.add(0, column, "Attribute column names must be attr:<name> with 1-100 characters");
                key = "attr:" + attribute;
            } else if (!CSV_COLUMNS.contains(key)) {
                errors.add(0, column.isEmpty() ? "(blank)" : column, "Unknown column");
            }
            if (!seen.add(key)) errors.add(0, column, "Duplicate column");
            header.add(key);
        }
        for (String required : List.of("slug", "name", "sku", "price")) {
            if (!header.contains(required)) errors.add(0, required, "Required column is missing");
        }
        if (errors.any()) return List.of();
        int dataRows = records.size() - 1;
        if (dataRows == 0) {
            errors.add(0, "file", "File has no data rows");
            return List.of();
        }
        if (dataRows > MAX_ROWS) {
            errors.add(0, "file", "File has " + dataRows + " rows; the maximum is " + MAX_ROWS);
            return List.of();
        }

        Map<String, ProductDraft> bySlug = new LinkedHashMap<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> values = records.get(r);
            if (values.stream().allMatch(String::isBlank)) continue;
            if (values.size() != header.size()) {
                errors.add(r, "row", "Expected " + header.size() + " fields but found " + values.size());
                continue;
            }
            Map<String, String> cells = new HashMap<>();
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                String value = values.get(c).trim();
                if (header.get(c).startsWith("attr:")) {
                    if (!value.isEmpty()) attributes.put(header.get(c).substring(5), value);
                } else {
                    cells.put(header.get(c), value.isEmpty() ? null : value);
                }
            }
            String slug = cells.get("slug");
            ProductDraft draft = slug == null ? null : bySlug.get(slug);
            if (draft == null) {
                draft = new ProductDraft(r);
                draft.slug = slug;
                draft.name = cells.get("name");
                draft.description = cells.get("description");
                draft.brand = cells.get("brand");
                draft.categories = splitCategories(cells.get("categories"));
                validateProduct(draft, r, "", errors);
                if (slug != null) bySlug.put(slug, draft);
                else bySlug.put("\u0000row" + r, draft);
            } else {
                requireSame(draft, r, "name", draft.name, cells.get("name"), errors);
                requireSame(draft, r, "description", draft.description, cells.get("description"), errors);
                requireSame(draft, r, "brand", draft.brand, cells.get("brand"), errors);
                if (cells.get("categories") != null
                        && !new LinkedHashSet<>(splitCategories(cells.get("categories")))
                                .equals(new LinkedHashSet<>(draft.categories)))
                    errors.add(r, "categories", "Conflicts with the categories given on row " + draft.row);
            }
            draft.variants.add(variant(
                    r,
                    "",
                    cells.get("sku"),
                    cells.get("price"),
                    attributes,
                    cells.get("barcode"),
                    cells.get("weight_grams"),
                    cells.get("length_mm"),
                    cells.get("width_mm"),
                    cells.get("height_mm"),
                    cells.get("active"),
                    errors));
        }
        return new ArrayList<>(bySlug.values());
    }

    private static void requireSame(
            ProductDraft draft, int row, String field, String first, String value, Errors errors) {
        if (value != null && !value.equals(first))
            errors.add(row, field, "Conflicts with the " + field + " given on row " + draft.row + " for this slug");
    }

    private static List<String> splitCategories(String value) {
        if (value == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : value.split("\\|")) {
            String slug = part.trim();
            if (!slug.isEmpty() && !result.contains(slug)) result.add(slug);
        }
        return result;
    }

    // ---- JSON ------------------------------------------------------------------------------

    private List<ProductDraft> parseJson(String content, Errors errors) {
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (JsonProcessingException e) {
            long line = e.getLocation() == null ? 0 : e.getLocation().getLineNr();
            errors.add(0, "file", "Invalid JSON" + (line > 0 ? " near line " + line : ""));
            return List.of();
        }
        if (root == null || !root.isObject() || !root.path("products").isArray()) {
            errors.add(0, "products", "Expected an object with a \"products\" array");
            return List.of();
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!"products".equals(name)) errors.add(0, name, "Unknown field");
        });
        JsonNode products = root.get("products");
        if (products.isEmpty()) {
            errors.add(0, "products", "File has no products");
            return List.of();
        }
        int variantCount = 0;
        for (JsonNode product : products)
            variantCount += product.path("variants").size();
        if (products.size() > MAX_ROWS || variantCount > MAX_ROWS) {
            errors.add(0, "products", "At most " + MAX_ROWS + " products and " + MAX_ROWS + " variants per file");
            return List.of();
        }
        List<ProductDraft> drafts = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            int row = i + 1;
            JsonNode node = products.get(i);
            if (!node.isObject()) {
                errors.add(row, "product", "Expected an object");
                continue;
            }
            node.fieldNames().forEachRemaining(name -> {
                if (!JSON_PRODUCT_FIELDS.contains(name)) errors.add(row, name, "Unknown field");
            });
            ProductDraft draft = new ProductDraft(row);
            draft.slug = text(node, "slug", row, "slug", errors);
            draft.name = text(node, "name", row, "name", errors);
            draft.description = text(node, "description", row, "description", errors);
            draft.brand = text(node, "brand", row, "brand", errors);
            JsonNode categories = node.get("categories");
            if (categories != null && !categories.isNull()) {
                if (!categories.isArray()) errors.add(row, "categories", "Expected an array of category slugs");
                else {
                    List<String> slugs = new ArrayList<>();
                    for (int c = 0; c < categories.size(); c++) {
                        JsonNode value = categories.get(c);
                        if (!value.isTextual()) errors.add(row, "categories[" + c + "]", "Expected a string");
                        else if (!value.asText().isBlank()
                                && !slugs.contains(value.asText().trim()))
                            slugs.add(value.asText().trim());
                    }
                    draft.categories = slugs;
                }
            }
            validateProduct(draft, row, "", errors);
            JsonNode variants = node.get("variants");
            if (variants == null || !variants.isArray() || variants.isEmpty()) {
                errors.add(row, "variants", "At least one variant is required");
            } else {
                for (int v = 0; v < variants.size(); v++) {
                    String prefix = "variants[" + v + "].";
                    JsonNode variant = variants.get(v);
                    if (!variant.isObject()) {
                        errors.add(row, "variants[" + v + "]", "Expected an object");
                        continue;
                    }
                    variant.fieldNames().forEachRemaining(name -> {
                        if (!JSON_VARIANT_FIELDS.contains(name)) errors.add(row, prefix + name, "Unknown field");
                    });
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    JsonNode attrs = variant.get("attributes");
                    if (attrs != null && !attrs.isNull()) {
                        if (!attrs.isObject()) errors.add(row, prefix + "attributes", "Expected an object");
                        else
                            attrs.properties().forEach(entry -> {
                                JsonNode value = entry.getValue();
                                if (value.isTextual())
                                    attributes.put(
                                            entry.getKey(), value.asText().trim());
                                else if (value.isNumber()) attributes.put(entry.getKey(), value.numberValue());
                                else if (value.isBoolean()) attributes.put(entry.getKey(), value.booleanValue());
                                else
                                    errors.add(
                                            row,
                                            prefix + "attributes." + entry.getKey(),
                                            "Attribute values must be strings, numbers or booleans");
                            });
                    }
                    draft.variants.add(variant(
                            row,
                            prefix,
                            text(variant, "sku", row, prefix + "sku", errors),
                            scalar(variant, "price", row, prefix + "price", errors),
                            attributes,
                            text(variant, "barcode", row, prefix + "barcode", errors),
                            scalar(variant, "weightGrams", row, prefix + "weightGrams", errors),
                            scalar(variant, "lengthMm", row, prefix + "lengthMm", errors),
                            scalar(variant, "widthMm", row, prefix + "widthMm", errors),
                            scalar(variant, "heightMm", row, prefix + "heightMm", errors),
                            scalar(variant, "active", row, prefix + "active", errors),
                            errors));
                }
            }
            drafts.add(draft);
        }
        return drafts;
    }

    private static String text(JsonNode node, String name, int row, String field, Errors errors) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            errors.add(row, field, "Expected a string");
            return null;
        }
        String trimmed = value.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Numbers/booleans are accepted as JSON scalars or strings and validated like CSV cells. */
    private static String scalar(JsonNode node, String name, int row, String field, Errors errors) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.decimalValue().toPlainString();
        if (value.isBoolean() || value.isTextual()) {
            String text = value.asText().trim();
            return text.isEmpty() ? null : text;
        }
        errors.add(row, field, "Expected a scalar value");
        return null;
    }

    // ---- Shared validation -----------------------------------------------------------------

    private static void validateProduct(ProductDraft draft, int row, String prefix, Errors errors) {
        if (draft.slug == null) errors.add(row, prefix + "slug", "Slug is required");
        else if (draft.slug.length() > 255 || !SLUG.matcher(draft.slug).matches())
            errors.add(
                    row,
                    prefix + "slug",
                    "Slug must be lowercase letters/digits separated by single hyphens (max 255)");
        if (draft.name == null) errors.add(row, prefix + "name", "Name is required");
        else if (draft.name.length() > 255) errors.add(row, prefix + "name", "Name must be at most 255 characters");
        if (draft.description != null && draft.description.length() > MAX_DESCRIPTION)
            errors.add(row, prefix + "description", "Description must be at most " + MAX_DESCRIPTION + " characters");
        if (draft.brand != null && draft.brand.length() > BrandService.MAX_NAME_LENGTH)
            errors.add(row, prefix + "brand", "Brand must be at most " + BrandService.MAX_NAME_LENGTH + " characters");
        if (draft.categories.size() > MAX_CATEGORIES)
            errors.add(row, prefix + "categories", "At most " + MAX_CATEGORIES + " categories per product");
    }

    private static VariantDraft variant(
            int row,
            String prefix,
            String sku,
            String price,
            Map<String, Object> attributes,
            String barcode,
            String weight,
            String length,
            String width,
            String height,
            String active,
            Errors errors) {
        if (sku == null) errors.add(row, prefix + "sku", "SKU is required");
        else if (sku.length() > 255) errors.add(row, prefix + "sku", "SKU must be at most 255 characters");
        else if (sku.chars().anyMatch(Character::isWhitespace))
            errors.add(row, prefix + "sku", "SKU must not contain whitespace");
        BigDecimal amount = null;
        if (price == null) errors.add(row, prefix + "price", "Price is required");
        else {
            try {
                amount = new BigDecimal(price);
                if (amount.signum() <= 0) {
                    errors.add(row, prefix + "price", "Price must be greater than 0");
                    amount = null;
                } else if (amount.scale() > 2) {
                    errors.add(row, prefix + "price", "Price must have at most 2 decimal places");
                    amount = null;
                } else if (amount.compareTo(MAX_PRICE) > 0) {
                    errors.add(row, prefix + "price", "Price must be at most " + MAX_PRICE);
                    amount = null;
                } else amount = amount.setScale(2);
            } catch (NumberFormatException e) {
                errors.add(row, prefix + "price", "Price must be a decimal number such as 19.99");
            }
        }
        if (attributes.size() > MAX_ATTRIBUTES)
            errors.add(row, prefix + "attributes", "At most " + MAX_ATTRIBUTES + " attributes per variant");
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 100)
                errors.add(row, prefix + "attributes", "Attribute names must be 1-100 characters");
            if (entry.getValue() instanceof String text && text.length() > 255)
                errors.add(
                        row,
                        prefix + "attributes." + entry.getKey(),
                        "Attribute values must be at most 255 characters");
        }
        if (barcode != null && barcode.length() > 64)
            errors.add(row, prefix + "barcode", "Barcode must be at most 64 characters");
        Integer weightGrams = integer(
                weight,
                1,
                ProductVariantService.MAX_WEIGHT_GRAMS,
                row,
                prefix + "weightGrams",
                prefix.isEmpty() ? "weight_grams" : null,
                errors);
        String lengthField = prefix.isEmpty() ? "length_mm" : prefix + "lengthMm";
        String widthField = prefix.isEmpty() ? "width_mm" : prefix + "widthMm";
        String heightField = prefix.isEmpty() ? "height_mm" : prefix + "heightMm";
        Integer lengthMm = integer(length, 1, ProductVariantService.MAX_DIMENSION_MM, row, lengthField, null, errors);
        Integer widthMm = integer(width, 1, ProductVariantService.MAX_DIMENSION_MM, row, widthField, null, errors);
        Integer heightMm = integer(height, 1, ProductVariantService.MAX_DIMENSION_MM, row, heightField, null, errors);
        int dimensions = (length == null ? 0 : 1) + (width == null ? 0 : 1) + (height == null ? 0 : 1);
        if (dimensions != 0 && dimensions != 3)
            errors.add(
                    row,
                    prefix.isEmpty() ? "dimensions" : prefix + "dimensions",
                    "Length, width and height must be given together");
        boolean isActive = true;
        if (active != null) {
            switch (active.toLowerCase(Locale.ROOT)) {
                case "true", "yes", "1" -> isActive = true;
                case "false", "no", "0" -> isActive = false;
                default -> errors.add(
                        row, prefix.isEmpty() ? "active" : prefix + "active", "Active must be true or false");
            }
        }
        return new VariantDraft(
                row, prefix, sku, amount, attributes, barcode, weightGrams, lengthMm, widthMm, heightMm, isActive);
    }

    private static Integer integer(
            String value, int min, int max, int row, String field, String csvField, Errors errors) {
        if (value == null) return null;
        String name = csvField == null ? field : csvField;
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.stripTrailingZeros().scale() > 0) throw new NumberFormatException();
            if (number.compareTo(BigDecimal.valueOf(min)) < 0 || number.compareTo(BigDecimal.valueOf(max)) > 0) {
                errors.add(row, name, "Must be between " + min + " and " + max);
                return null;
            }
            return number.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            errors.add(row, name, "Must be a whole number");
            return null;
        }
    }

    /** File-wide uniqueness plus database checks; returns category ids by slug. */
    private Map<String, Long> validateAgainstDatabase(List<ProductDraft> drafts, Errors errors) {
        Map<String, Integer> slugRows = new HashMap<>();
        Map<String, Integer> skuRows = new HashMap<>();
        Set<String> categorySlugs = new HashSet<>();
        for (ProductDraft draft : drafts) {
            Integer previous = slugRows.putIfAbsent(draft.slug, draft.row);
            if (previous != null)
                errors.add(draft.row, "slug", "Duplicate slug in file (first used on row " + previous + ")");
            categorySlugs.addAll(draft.categories);
            for (VariantDraft variant : draft.variants) {
                Integer first = skuRows.putIfAbsent(variant.sku(), variant.row());
                if (first != null)
                    errors.add(
                            variant.row(),
                            variant.prefix() + "sku",
                            "Duplicate SKU in file (first used on row " + first + ")");
            }
        }
        for (String slug : productRepository.findExistingSlugs(slugRows.keySet()))
            errors.add(slugRows.get(slug), "slug", "A product with slug '" + slug + "' already exists");
        Map<String, VariantDraft> bySku = new HashMap<>();
        drafts.forEach(d -> d.variants.forEach(v -> bySku.putIfAbsent(v.sku(), v)));
        for (String sku : variantRepository.findExistingSkus(skuRows.keySet())) {
            VariantDraft variant = bySku.get(sku);
            errors.add(variant.row(), variant.prefix() + "sku", "A variant with SKU '" + sku + "' already exists");
        }
        Map<String, Long> categoryIds = new HashMap<>();
        if (!categorySlugs.isEmpty())
            for (Category category : categoryRepository.findAllBySlugIn(categorySlugs))
                categoryIds.put(category.getSlug(), category.getId());
        for (ProductDraft draft : drafts) {
            for (String slug : draft.categories) {
                if (!categoryIds.containsKey(slug))
                    errors.add(draft.row, "categories", "Unknown category slug '" + slug + "'");
            }
        }
        errors.list.sort(java.util.Comparator.comparingInt(RowError::row));
        return categoryIds;
    }

    private Result create(List<ProductDraft> drafts, Map<String, Long> categoryIds) {
        Set<String> newBrands = new HashSet<>();
        int variants = 0;
        for (ProductDraft draft : drafts) {
            var product = productService.create(
                    draft.name,
                    draft.slug,
                    draft.description,
                    draft.categories.stream().map(categoryIds::get).toList());
            if (draft.brand != null) {
                String key = draft.brand.toLowerCase(Locale.ROOT);
                if (!newBrands.contains(key)
                        && brandRepository.findByNameIgnoreCase(draft.brand).isEmpty()) newBrands.add(key);
                brandService.assign(
                        product.getId(), brandService.getOrCreate(draft.brand).getId());
            }
            for (VariantDraft variant : draft.variants) {
                var created = variantService.create(
                        product.getId(),
                        variant.sku(),
                        variant.price(),
                        variant.attributes(),
                        variant.barcode(),
                        variant.weightGrams(),
                        variant.lengthMm(),
                        variant.widthMm(),
                        variant.heightMm());
                if (!variant.active()) variantService.update(created.getId(), null, null, null, null, null, false);
                variants++;
            }
        }
        productRepository.flush();
        return new Result(true, drafts.size(), variants, newBrands.size(), List.of());
    }
}
