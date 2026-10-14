package ee.bytecore.backend.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Encrypts a String column at rest; reads legacy plaintext transparently. */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {
    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : FieldEncryption.required().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : FieldEncryption.required().decrypt(dbData);
    }
}
