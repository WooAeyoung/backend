package kr.co.wooaeyoung.integration;

public class IntegrationException extends RuntimeException {
    public IntegrationException(String message) { super(message); }
    public IntegrationException(String message, Throwable cause) { super(message, cause); }
}
