package kr.co.wooaeyoung.domain.standard;

public class StandardDataException extends IllegalStateException {
    public StandardDataException(String message) { super(message); }
    public StandardDataException(String message, Throwable cause) { super(message, cause); }
}
