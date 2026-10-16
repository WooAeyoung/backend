package kr.co.wooaeyoung.standards;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import kr.co.wooaeyoung.api.ApiModels.Species;

/** 종별로 0 초과 1 이하인 값(대사체중 지수, 주의 비율). */
public record SpeciesFraction(
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double dog,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double cat) {
    public double of(Species species) { return species == Species.DOG ? dog : cat; }
}