package kr.co.wooaeyoung.domain;

public class ExternalServiceUnavailableException extends RuntimeException {
    public ExternalServiceUnavailableException(String message) { super(message); }
    public ExternalServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
