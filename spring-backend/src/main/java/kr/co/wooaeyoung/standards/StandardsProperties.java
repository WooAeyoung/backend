package kr.co.wooaeyoung.standards;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** allowUnverified: 검증 대기(PENDING_VERIFICATION) 기준값을 판정·추천에 쓸지 여부. test 프로필에서만 true를 허용한다. */
@ConfigurationProperties(prefix = "wooaeyoung.standards")
public record StandardsProperties(boolean allowUnverified) {}
