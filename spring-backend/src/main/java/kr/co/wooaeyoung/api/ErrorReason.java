package kr.co.wooaeyoung.api;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 오류 응답 detail.reason(BE-V3-015). 기존 detail.code와 HTTP 상태는 바꾸지 않고 세부 사유만 더한다.
 * 계산 오류(CALCULATION_INPUT_ERROR)는 AnalysisException 메시지로 분류한다. 메시지를 만드는 계산 코드(NiasV3Strategy,
 * FediafStrategy, CatalogService 등)를 고치지 않기 위해서다. 발생 지점과 패턴의 대응은 ErrorReasonContractTest가 소스를 읽어 확인한다.
 * 어느 패턴에도 맞지 않는 메시지는 INPUT_ERROR다.
 */
public enum ErrorReason {
    /** 미지원: 8주 미만 등 계산 범위 밖 개체(NIAS 고양이·강아지, FEDIAF). 명세 T7의 "전 성분 미지원" 대신 422로 막는다(결정 D1). */
    AGE_UNSUPPORTED("^\\d+주 미만 개체는 현재 지원하지 않습니다\\.$"),
    /** 체급: NIAS 성장기 강아지에 adultSize 없음(T8). */
    SIZE_CLASS_REQUIRED("^체급 선택 필요: "),
    /** 체급: 성장곡선에 없는 adultSize. */
    SIZE_CLASS_INVALID("^지원하지 않는 체급입니다: "),
    /** 정보 부족: FEDIAF 12개월 미만 개의 예상 성체 체중. */
    EXPECTED_ADULT_WEIGHT_REQUIRED("^12개월 미만 개는 현재 체중 이상인 예상 성체 체중이 필요합니다\\.$"),
    /** 제품 없음. */
    PRODUCT_NOT_FOUND("^(바코드 )?제품을 찾을 수 없습니다: "),
    /** 단위: 카탈로그 제품의 급여 단위와 요청 단위가 다름. */
    UNIT_MISMATCH("의 급여 단위는 .+입니다\\.$"),
    /** 단위: 기준 단위로 환산할 수 없는 단위(질량 성분에 IU 등). */
    UNIT_UNSUPPORTED("^변환할 수 없는 단위입니다: "),
    /** 미지원: 이 기준 프로필에서 분석하지 않는 성분. */
    NUTRIENT_UNSUPPORTED("^지원하지 않는 성분입니다: ", "^알 수 없는 성분: "),
    DUPLICATE_NUTRIENT("^중복 성분입니다: "),
    NO_ITEMS("^분석할 사료 또는 영양제를 하나 이상 추가해주세요\\.$"),
    TOO_MANY_ITEMS("^제품은 최대 \\d+개까지 분석할 수 있습니다\\.$"),
    /** 라벨 표기 오류(UnitConversionService가 거부한 수동 입력: 원료 형태·표기 기준·수분·음수 함량 등, NIAS_V3). */
    LABEL_INVALID(
            "^함량은 0 이상의 유한한 값이어야 합니다: ",
            "^원료 형태 \\S+는 \\S+ 성분용입니다: ",
            "^수분\\(%\\)은 0 이상 100 미만이어야 합니다: ",
            "^EPA와 DHA의 표기 기준\\(1 g당/1회 제공량당\\)이 다릅니다\\.$",
            "^EPA_DHA와 EPA·DHA를 함께 둘 수 없음$",
            "^BETA_CAROTENE의 원료 형태는 BETA_CAROTENE뿐입니다: ",
            "^VITAMIN_A와 BETA_CAROTENE의 표기 기준\\(1 g당/1회 제공량당\\)이 다릅니다\\.$",
            "^\\S+: % 단위는 PERCENT 또는 DRY_MATTER_PERCENT 표기에만 씁니다\\(",
            "^\\S+: \\S+ 표기는 % 단위여야 합니다\\(",
            "^\\S+: ppm은 PER_KG\\(mg/kg\\) 표기입니다\\("),
    /** 분류되지 않은 계산 입력 오류(기본값). */
    INPUT_ERROR,
    /** 기준 없음: 알 수 없는 standardProfile(경로·쿼리·본문). HTTP 400 BAD_REQUEST에 붙는다. */
    STANDARD_PROFILE_UNKNOWN;

    private final List<Pattern> patterns;

    ErrorReason(String... patterns) { this.patterns = Arrays.stream(patterns).map(Pattern::compile).toList(); }

    /** CALCULATION_INPUT_ERROR 메시지의 사유. 맞는 패턴이 없으면 INPUT_ERROR. */
    public static ErrorReason ofCalculationMessage(String message) {
        if (message != null)
            for (ErrorReason reason : values())
                if (reason.patterns.stream().anyMatch(pattern -> pattern.matcher(message).find())) return reason;
        return INPUT_ERROR;
    }

    /** BAD_REQUEST의 사유. 기준 프로필 값이 잘못된 경우만 분류하고 나머지는 null(키 없음)이다. */
    public static ErrorReason ofBadRequest(Throwable error) {
        if (error instanceof MethodArgumentTypeMismatchException mismatch && mismatch.getRequiredType() == StandardProfile.class) return STANDARD_PROFILE_UNKNOWN;
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof InvalidFormatException format && format.getTargetType() == StandardProfile.class) return STANDARD_PROFILE_UNKNOWN;
        return null;
    }
}
