package ee.bytecore.backend.exceptions;

public class IdentitySyncException extends RuntimeException {
    public IdentitySyncException(String message) {
        super(message);
    }

    public IdentitySyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
