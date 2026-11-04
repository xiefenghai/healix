package com.healix.core.vitals.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healix.common.exception.BusinessException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class VitalValueRulesTest {

    @Test
    void acceptsInRangeBloodPressure() {
        assertThat(VitalValueRules.isPlausible("BLOOD_PRESSURE_SYS", new BigDecimal("120"))).isTrue();
        assertThat(VitalValueRules.isPlausible("BLOOD_PRESSURE_DIA", new BigDecimal("80"))).isTrue();
    }

    @Test
    void rejectsEmptyAsZeroAndOutOfRange() {
        assertThat(VitalValueRules.isPlausible("BLOOD_PRESSURE_SYS", BigDecimal.ZERO)).isFalse();
        assertThat(VitalValueRules.isPlausible("BLOOD_PRESSURE_SYS", new BigDecimal("400"))).isFalse();
        assertThatThrownBy(() -> VitalValueRules.assertPlausible("BLOOD_PRESSURE_SYS", BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收缩压");
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> VitalValueRules.assertPlausible("HEART_RATE", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请填写");
    }
}
