package ee.bytecore.backend.utils;

import ee.bytecore.backend.enums.UserRole;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserRoleConverter implements AttributeConverter<UserRole, String> {

  @Override
  public String convertToDatabaseColumn(UserRole role) {
    return role == null ? null : role.getValue();
  }

  @Override
  public UserRole convertToEntityAttribute(String value) {
    if (value == null) {
      return null;
    }

    return UserRole.valueOf(value.toUpperCase());
  }
}
