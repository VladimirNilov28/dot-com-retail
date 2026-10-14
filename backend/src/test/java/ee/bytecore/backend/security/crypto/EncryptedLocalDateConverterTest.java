package ee.bytecore.backend.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class EncryptedLocalDateConverterTest {
    private final EncryptedLocalDateConverter converter = new EncryptedLocalDateConverter();
    private FieldCipher previous;

    @BeforeEach
    void install() {
        try {
            previous = FieldEncryption.required();
        } catch (IllegalStateException e) {
            previous = null;
        }
        FieldEncryption.install(
                FieldCipher.fromKeyRing("t1:" + Base64.getEncoder().encodeToString(new byte[32]), null));
    }

    @AfterEach
    void restore() {
        FieldEncryption.install(previous);
    }

    @Test
    void storesCiphertextAndReadsItBack() {
        String stored = converter.convertToDatabaseColumn(LocalDate.of(1990, 4, 12));
        assertThat(stored).startsWith("enc:t1:").doesNotContain("1990", "04-12");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(LocalDate.of(1990, 4, 12));
    }

    @Test
    void readsLegacyPlaintextIsoDatesAndNulls() {
        assertThat(converter.convertToEntityAttribute("1985-05-05")).isEqualTo(LocalDate.of(1985, 5, 5));
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }
}
