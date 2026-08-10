package ee.bytecore.backend.enums;

public enum UserRole {
  ADMIN("admin"),
  USER("user"),
  SUPPORT("support");

  private final String value;

  UserRole(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }
}