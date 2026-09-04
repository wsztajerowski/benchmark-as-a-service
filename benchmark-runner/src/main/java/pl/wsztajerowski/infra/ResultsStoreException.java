package pl.wsztajerowski.infra;

/** Thrown when a run's measurements could not be stored. Fatal: the run must exit non-zero. */
public class ResultsStoreException extends RuntimeException {

    public ResultsStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResultsStoreException(String message) {
        super(message);
    }
}
