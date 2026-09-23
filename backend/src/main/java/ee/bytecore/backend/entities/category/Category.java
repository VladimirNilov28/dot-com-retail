package ee.bytecore.backend.entities.category;

import java.time.Instant;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@Entity
@Table(name = "categories")
public class Category {
    protected Category() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull @Size(max = 255) @Column(name = "name", nullable = false)
    private String name;

    @NotNull @Size(max = 255) @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Category create(String name, String slug, Category parent) {
        Category category = new Category();
        category.name = name;
        category.slug = slug;
        category.parent = parent;
        return category;
    }
}
