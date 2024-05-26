package pl.wsztajerowski;

public class JavaWonderlandException extends RuntimeException {
    public JavaWonderlandException(Throwable e) {
        super(e);
    }
    public JavaWonderlandException(String cause) {
        super(cause);
    }
}
