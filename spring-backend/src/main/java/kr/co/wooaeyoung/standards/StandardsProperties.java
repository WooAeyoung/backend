package kr.co.wooaeyoung.standards;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** allowUnverified: 검증 대기(PENDING_VERIFICATION) 기준값을 참고 판정·추천에 쓸지 여부. */
@ConfigurationProperties(prefix = "wooaeyoung.standards")
public record StandardsProperties(boolean allowUnverified) {}
