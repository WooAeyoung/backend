package kr.co.wooaeyoung.api;

public class UnknownStandardProfileException extends RuntimeException {
    public UnknownStandardProfileException(String value) {
        super("standardProfile: FEDIAF_2025 또는 NIAS_V3여야 합니다. 입력값: " + value);
    }
}
