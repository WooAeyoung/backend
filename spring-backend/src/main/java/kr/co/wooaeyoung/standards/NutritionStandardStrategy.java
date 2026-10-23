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
    /** GET /api/v1/nutrients?standardProfile= 의 전체 성분 목록(분석 대상이 아닌 성분 포함). */
    List<Map<String, Object>> nutrientCatalog();

    /** 개체 입력이 이 기준으로 계산 가능한지 검사한다. 불가능하면 AnalysisException. */
    void validate(Profile profile);
    String lifeStage(Profile profile);
    /** 하루 기준 열량(kcal). 이 프로필에서 계산하지 않는 생애주기는 null. */
    Double energy(Profile profile, String stage);
    /** 분석 응답 energyBasis: 기준 열량의 계산 근거(생애주기, k와 그 근거, 지수, 성장곡선). 제공하지 않는 프로필은 null. */
    default Map<String, Object> energyBasis(Profile profile, String stage, Double kcal) { return null; }
    /** 분석 대상 성분별 기준선과 근거. */
    Map<String, NutrientLine> lines(Profile profile, String stage, Double kcal);
    /** 개의 Ca:P를 판정하지 않는 이유(UNSUPPORTED, UNVERIFIED_STANDARD). 판정하면 null. */
    String calciumPhosphorusBlockedStatus(String stage);
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
