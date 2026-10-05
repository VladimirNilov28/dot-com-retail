package ee.bytecore.backend.exceptions;

public class GuestCartUnavailableException extends RuntimeException {
    public GuestCartUnavailableException() {
        super("Guest cart is unavailable; start a new cart");
    }
}
