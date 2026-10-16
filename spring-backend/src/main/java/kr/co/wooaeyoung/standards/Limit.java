package kr.co.wooaeyoung.standards;

/** 개체의 하루 기준선. 값이 없으면 null이며 0으로 바꾸지 않는다. */
public record Limit(Double minimum, Double caution, Double upper) {}
