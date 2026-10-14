package ee.bytecore.backend.services.media;

/** Thrown for uploads that are rejected before anything is stored. */
public class InvalidImageException extends RuntimeException {
    private final int status;

    public InvalidImageException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
