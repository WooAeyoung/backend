package kr.co.wooaeyoung.standards;

import kr.co.wooaeyoung.api.ApiModels.Profile;
import kr.co.wooaeyoung.api.ApiModels.Species;

import java.util.List;
import java.util.Map;

/** 기준 프로필별 계산 규칙. 프로필 간에 가변 상태를 공유하지 않는다. */
public interface NutritionStandardStrategy {
    StandardProfile profile();
    String parameterVersion();
    /** 매개변수 섹션별 검증 상태. */
    Map<String, VerificationStatus> parameterVerification();
    /** 분석 대상 성분(응답 순서). */
    List<NutrientMeta> nutrients();

    /** 개체 입력이 이 기준으로 계산 가능한지 검사한다. 불가능하면 AnalysisException. */
    void validate(Profile profile);
    String lifeStage(Profile profile);
    /** 하루 기준 열량(kcal). 이 프로필에서 계산하지 않는 생애주기는 null. */
    Double energy(Profile profile, String stage);
    Map<String, Limit> thresholds(Profile profile, String stage, Double kcal);
    String calciumPhosphorusStatus(Profile profile, double calcium, double phosphorus);

    String standardSource();
    String cautionPolicy(Species species);
    List<String> analysisWarnings(Profile profile, String stage);

    /** 상세 성분이 없는 완전사료의 사료 몫 = 하한 × feedFactor. */
    double feedFactor();
    int defaultMaxItems();
    double recommendationScore(long fixed, long overlap);
    /** 추천을 계산하지 않는 프로필이면 그 이유, 계산하면 null. */
    String recommendationUnsupportedReason();
}
