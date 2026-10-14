package ee.bytecore.backend.exceptions;

public class CheckoutException extends IllegalArgumentException {
    private final String code;

    public CheckoutException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
