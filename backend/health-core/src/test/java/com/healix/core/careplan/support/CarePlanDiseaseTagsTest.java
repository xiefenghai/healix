package com.healix.core.careplan.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarePlanDiseaseTagsTest {

    @Test
    void recommendMultiDiseaseAsComprehensive() {
        assertThat(CarePlanDiseaseTags.recommend(List.of("DIABETES", "HYPERTENSION")))
                .isEqualTo(CarePlanTemplateKeyEnum.COMPREHENSIVE);
    }

    @Test
    void recommendSingleAndWellness() {
        assertThat(CarePlanDiseaseTags.recommend(List.of("糖尿病"))).isEqualTo(CarePlanTemplateKeyEnum.DIABETES);
        assertThat(CarePlanDiseaseTags.recommend(List.of("肥胖"))).isEqualTo(CarePlanTemplateKeyEnum.OBESITY);
        assertThat(CarePlanDiseaseTags.recommend(List.of())).isEqualTo(CarePlanTemplateKeyEnum.WELLNESS);
    }

    @Test
    void specialtyMismatchOnlyForSpecialtyWithoutArchive() {
        assertThat(CarePlanDiseaseTags.specialtyMismatch(CarePlanTemplateKeyEnum.DIABETES, List.of()))
                .isTrue();
        assertThat(CarePlanDiseaseTags.specialtyMismatch(CarePlanTemplateKeyEnum.WELLNESS, List.of()))
                .isFalse();
        assertThat(CarePlanDiseaseTags.specialtyMismatch(
                        CarePlanTemplateKeyEnum.COMPREHENSIVE, List.of()))
                .isFalse();
        assertThat(CarePlanDiseaseTags.specialtyMismatch(
                        CarePlanTemplateKeyEnum.DIABETES, List.of("DIABETES")))
                .isFalse();
    }

    @Test
    void legacyKeysCanonicalize() {
        assertThat(CarePlanTemplateKeyEnum.GENERAL.canonical()).isEqualTo(CarePlanTemplateKeyEnum.WELLNESS);
        assertThat(CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION.canonical())
                .isEqualTo(CarePlanTemplateKeyEnum.COMPREHENSIVE);
        assertThat(CarePlanTemplateKeyEnum.parseOrNull("GENERAL")).isEqualTo(CarePlanTemplateKeyEnum.WELLNESS);
    }
}
