package ee.bytecore.backend.security.crypto;

import java.time.LocalDate;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a date as AES-GCM ciphertext of its ISO-8601 form; reads legacy plaintext ISO dates transparently. */
@Converter
public class EncryptedLocalDateConverter implements AttributeConverter<LocalDate, String> {
    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        return attribute == null ? null : FieldEncryption.required().encrypt(attribute.toString());
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        return dbData == null
                ? null
                : LocalDate.parse(FieldEncryption.required().decrypt(dbData));
    }
}
