package satisfactory.spi;

import java.util.List;

/** Raised when a dataset cannot be turned into a planning problem at all. The details go back to the caller. */
public final class InvalidDataset extends Exception {

    private final List<String> details;

    public InvalidDataset(String message, List<String> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public InvalidDataset(String message) {
        this(message, List.of(message));
    }

    public List<String> details() {
        return details;
    }
}
