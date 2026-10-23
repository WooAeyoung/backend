package kr.co.wooaeyoung.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.domain.ProductDataRegistry.ProductData;
import kr.co.wooaeyoung.standards.NutrientMaster;
import kr.co.wooaeyoung.standards.StandardParametersLoader;
import kr.co.wooaeyoung.units.Conversion;
import kr.co.wooaeyoung.units.Conversion.Status;
import kr.co.wooaeyoung.units.UnitConversionFactors;
import kr.co.wooaeyoung.units.UnitConversionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** BE-V3-011 제품 스키마와 시작 시 검증. 제품 데이터 오류는 계산 중 예외가 아니라 컨텍스트 로딩 실패로 나타나야 한다. */
@ExtendWith(OutputCaptureExtension.class)
class ProductDataRegistryTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(StandardParametersLoader.class, NutrientMaster.class, UnitConversionFactors.class,
                    UnitConversionService.class, ProductDataRegistry.class);

    private ProductDataRegistry load(String location) {
        ProductDataRegistry[] holder = new ProductDataRegistry[1];
        runner.withPropertyValues("wooaeyoung.catalog.products-resource=" + location).run(context -> {
            assertThat(context).hasNotFailed();
            holder[0] = context.getBean(ProductDataRegistry.class);
        });
        return holder[0];
    }

    private static void converted(double expected, boolean perGram, Conversion conversion) {
        assertNotNull(conversion);
        assertEquals(Status.CONVERTED, conversion.status(), conversion.toString());
        assertEquals(expected, conversion.value(), Math.abs(expected) * 1e-12, conversion.toString());
        assertEquals(perGram, conversion.perGram(), conversion.toString());
    }

    @Test @DisplayName("기존 products.json은 값 변경 없이 적재되고, 사료는 1 g당·영양제는 1회 제공량당으로 정규화된다")
    void existingProductsLoadWithDefaults() {
        ProductDataRegistry registry = load(ProductDataRegistry.DEFAULT_LOCATION);
        assertEquals(7, registry.all().size());
        ProductData feed = registry.find("feed-balanced-dog").orElseThrow();
        assertTrue(feed.hasDetail());
        assertEquals(ProductDataRegistry.ContentBasis.PER_SERVING, feed.contentBasis());
        assertEquals(List.of(), feed.tags());
        assertNull(feed.updatedAt());
        Map<String, Conversion> dog = feed.contents(Species.DOG);
        converted(0.009, true, dog.get("CALCIUM"));            // 900 mg / 100 g → 0.9 g ÷ 100 g
        // 반올림하지 않으므로 0.9 ÷ 100의 부동소수점 표현(0.009000000000000001)이 그대로 남는다.
        assertTrue(dog.get("CALCIUM").formula().startsWith("900 mg = 0.9 g; 0.9 g ÷ 100 g(1회 제공량) = "), dog.get("CALCIUM").formula());
        converted(0.0072, true, dog.get("PHOSPHORUS"));
        converted(0.12, true, dog.get("ZINC"));
        // 원료 형태가 없는 비타민 D(µg)·E(mg)는 추측하지 않는다.
        assertEquals(Status.INSUFFICIENT_DATA, dog.get("VITAMIN_D").status());
        assertEquals(Status.INSUFFICIENT_DATA, dog.get("VITAMIN_E").status());
        assertEquals("UG", dog.get("VITAMIN_D").originalUnit());
        // 총 오메가3만 있으면 EPA+DHA는 정보 없음.
        assertEquals(Status.NO_VALUE, dog.get("EPA_DHA").status());
        assertFalse(dog.containsKey("OMEGA3"));
        ProductData unknown = registry.find("feed-complete-unknown").orElseThrow();
        assertFalse(unknown.hasDetail());
        assertTrue(unknown.contents(Species.DOG).isEmpty());
        ProductData supplement = registry.find("supp-multi").orElseThrow();
        converted(4, false, supplement.contents(Species.DOG).get("ZINC"));
    }

    @Test @DisplayName("새 스키마 필드: 표기 형태·원료 형태·보증값·EPA+DHA·베타카로틴·1 g당 영양제")
    void newSchemaFields() {
        ProductDataRegistry registry = load("products-valid/schema-fields.json");
        ProductData feed = registry.find("feed-label").orElseThrow();
        assertEquals(List.of("피부"), feed.tags());
        assertEquals("테스트 라벨", feed.dataSource());
        assertEquals(LocalDate.of(2026, 10, 23), feed.updatedAt());
        Map<String, Conversion> dog = feed.contents(Species.DOG);
        converted(0.12, true, dog.get("ZINC"));
        converted(0.012, true, dog.get("CALCIUM"));
        assertEquals(Status.NO_VALUE, dog.get("PHOSPHORUS").status());
        converted(2.5, true, dog.get("VITAMIN_D"));
        converted(1000.0 / 100, true, dog.get("VITAMIN_A"));    // 344 µg 아세테이트 = 1000 IU / 100 g
        assertEquals(Status.INSUFFICIENT_DATA, dog.get("VITAMIN_E").status());
        converted(3 * 3800.0 / 1000 / 1000, true, dog.get("COPPER"));
        converted(0.2 * (1 - 10.0 / 100) / 100, true, dog.get("MAGNESIUM"));
        converted(0.2 / 100, true, dog.get("EPA_DHA"));
        assertEquals("EPA·DHA 합산", dog.get("EPA_DHA").note());

        ProductData beta = registry.find("supp-beta").orElseThrow();
        converted(833, false, beta.contents(Species.DOG).get("VITAMIN_A"));
        assertEquals(Status.EXCLUDED, beta.contents(Species.CAT).get("VITAMIN_A").status());
        assertEquals(Status.NO_VALUE, beta.contents(Species.CAT).get("EPA_DHA").status());

        Conversion powder = registry.find("supp-per-gram").orElseThrow().contents(Species.DOG).get("ZINC");
        converted(5, true, powder);
        assertTrue(powder.formula().contains("contentBasis=PER_GRAM"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "unknown-nutrient         | 알 수 없는 성분 ID: VITAMIN_Z",
            "negative-amount          | 음수이거나 잘못된 함량: CALCIUM",
            "invalid-unit             | 잘못된 단위: CALCIUM CFU",
            "mass-nutrient-in-iu      | 잘못된 단위: ZINC IU",
            "overlapping-dose         | doseByWeight 구간이 정렬되지 않았거나 겹침: 10.0 → 10.0",
            "unsorted-dose            | doseByWeight 구간이 정렬되지 않았거나 겹침: 20.0 → 10.0",
            "open-band-not-last       | 상한 없는 구간(maxKg null)은 마지막에만",
            "form-mismatch            | 원료 형태 RETINOL는 VITAMIN_A 성분용입니다: VITAMIN_D",
            "bad-date                 | updatedAt은 ISO 날짜",
            "has-detail-contradiction | hasDetail=true인데 성분 값은 없음",
            "duplicate-id             | 중복 제품 ID",
            "label-only-without-unit  | EPA는 단위가 정해지지 않아",
    })
    @DisplayName("잘못된 제품 데이터는 컨텍스트 로딩 실패")
    void invalidProductDataFailsContext(String file, String expectedMessage) {
        runner.withPropertyValues("wooaeyoung.catalog.products-resource=products-invalid/" + file + ".json").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedMessage);
        });
    }

    @Test @DisplayName("알 수 없는 필드는 적재를 막지 않고, 제품·필드를 밝힌 WARN 로그만 남긴다")
    void unknownFieldsOnlyWarn(CapturedOutput output) {
        ProductDataRegistry registry = load("products-valid/unknown-fields.json");
        assertEquals(List.of(
                "p1: 알 수 없는 필드 calories (무시함)",
                "p1: 알 수 없는 필드 nutrientDetails.ZINC.labelText (무시함)",
                "p1: 알 수 없는 필드 doseByWeight[0].memo (무시함)"), registry.warnings());
        converted(4, false, registry.find("p1").orElseThrow().contents(Species.DOG).get("ZINC"));
        assertThat(output.getOut()).contains("WARN").contains("p1: 알 수 없는 필드 calories (무시함)");
        assertEquals(List.of(), load(ProductDataRegistry.DEFAULT_LOCATION).warnings());
    }

    @Test void missingFileFailsContext() {
        runner.withPropertyValues("wooaeyoung.catalog.products-resource=products-invalid/none.json")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test @DisplayName("CatalogService와 같은 제품 ID 집합을 적재한다")
    void sameIdsAsCatalog() throws Exception {
        CatalogService catalog = new CatalogService(new ObjectMapper());
        assertEquals(catalog.all().stream().map(CatalogService.Product::id).toList(),
                load(ProductDataRegistry.DEFAULT_LOCATION).all().stream().map(ProductData::id).toList());
    }
}