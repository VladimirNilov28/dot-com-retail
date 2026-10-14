package ee.bytecore.backend.entities.inventory;

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
@Table(name = "warehouses")
public class Warehouse {
    protected Warehouse() {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull @Size(max = 255) @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 255) @Column(name = "location")
    private String location;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Warehouse create(String name, String location) {
        Warehouse warehouse = new Warehouse();
        warehouse.name = name;
        warehouse.location = location;
        return warehouse;
    }
}
