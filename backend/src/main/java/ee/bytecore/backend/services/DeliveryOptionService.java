package ee.bytecore.backend.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.CheckoutSettings;
import ee.bytecore.backend.config.CheckoutSettings.ConfiguredOption;
import ee.bytecore.backend.entities.shipping.DeliveryOption;
import ee.bytecore.backend.repositories.shipping.DeliveryOptionRepository;
import ee.bytecore.backend.services.CheckoutValues.ShippingOption;

import jakarta.persistence.EntityNotFoundException;

/**
 * Staff-managed delivery options. Checkout reads the active rows; placed orders keep their
 * own shipping snapshot, so edits here only affect future quotes.
 */
@Service
public class DeliveryOptionService {
    public static final Set<String> CODES = Set.of("STANDARD", "EXPRESS", "PICKUP");
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    private final DeliveryOptionRepository repository;
    private final CheckoutSettings settings;

    public DeliveryOptionService(DeliveryOptionRepository repository, CheckoutSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    public record Values(
            String name,
            String description,
            BigDecimal price,
            String currency,
            Integer estimatedMinDays,
            Integer estimatedMaxDays,
            Boolean active,
            Integer sortOrder) {}

    @Transactional(readOnly = true)
    public List<DeliveryOption> findAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public List<ShippingOption> checkoutOptions(String countryCode) {
        List<ConfiguredOption> configured = repository.findAllByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(option -> new ConfiguredOption(option.getCode(), option.getPrice(), estimate(option)))
                .toList();
        return settings.options(countryCode, configured);
    }

    @Transactional
    public DeliveryOption create(String code, Values values) {
        String normalized = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CODES.contains(normalized))
            throw new IllegalArgumentException("Delivery option code must be one of STANDARD, EXPRESS or PICKUP");
        if (repository.findByCode(normalized).isPresent())
            throw new IllegalArgumentException("A delivery option with code " + normalized + " already exists");
        if (values.name() == null
                || values.price() == null
                || values.estimatedMinDays() == null
                || values.estimatedMaxDays() == null)
            throw new IllegalArgumentException("Name, price and estimated delivery days are required");
        DeliveryOption option = DeliveryOption.create(normalized);
        apply(option, values);
        return repository.save(option);
    }

    @Transactional
    public DeliveryOption update(Long id, Values values) {
        DeliveryOption option = find(id);
        apply(option, values);
        return repository.saveAndFlush(option);
    }

    @Transactional
    public DeliveryOption delete(Long id) {
        DeliveryOption option = find(id);
        repository.delete(option);
        return option;
    }

    private DeliveryOption find(Long id) {
        return repository.findById(id).orElseThrow(() -> new EntityNotFoundException("Delivery option not found"));
    }

    private static void apply(DeliveryOption option, Values values) {
        if (values.name() != null) {
            String name = values.name().trim();
            if (name.isEmpty() || name.length() > 100)
                throw new IllegalArgumentException("Name must be 1-100 characters");
            option.setName(name);
        }
        if (values.description() != null) {
            String description = values.description().trim();
            if (description.length() > 255)
                throw new IllegalArgumentException("Description must be at most 255 characters");
            option.setDescription(description.isEmpty() ? null : description);
        }
        if (values.price() != null) {
            BigDecimal price = values.price();
            if (price.signum() < 0 || price.stripTrailingZeros().scale() > 2 || price.compareTo(MAX_PRICE) > 0)
                throw new IllegalArgumentException("Price must be a non-negative amount with at most 2 decimals");
            option.setPrice(price.setScale(2));
        }
        if (values.currency() != null) {
            if (!"EUR".equals(values.currency().trim().toUpperCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Only EUR delivery prices are supported");
            option.setCurrency("EUR");
        }
        if (values.estimatedMinDays() != null) option.setEstimatedMinDays(days(values.estimatedMinDays()));
        if (values.estimatedMaxDays() != null) option.setEstimatedMaxDays(days(values.estimatedMaxDays()));
        if (option.getEstimatedMinDays() > option.getEstimatedMaxDays())
            throw new IllegalArgumentException("Minimum delivery days cannot exceed maximum delivery days");
        if (values.active() != null) option.setActive(values.active());
        if (values.sortOrder() != null) {
            if (values.sortOrder() < 0 || values.sortOrder() > 1000)
                throw new IllegalArgumentException("Sort order must be between 0 and 1000");
            option.setSortOrder(values.sortOrder());
        }
    }

    private static int days(int value) {
        if (value < 0 || value > 365) throw new IllegalArgumentException("Delivery days must be between 0 and 365");
        return value;
    }

    static String estimate(DeliveryOption option) {
        if (option.getDescription() != null && !option.getDescription().isBlank()) return option.getDescription();
        int min = option.getEstimatedMinDays();
        int max = option.getEstimatedMaxDays();
        if (min == max) return min + (min == 1 ? " business day" : " business days");
        return min + "-" + max + " business days";
    }
}
