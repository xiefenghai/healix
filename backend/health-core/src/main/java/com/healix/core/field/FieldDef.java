package com.healix.core.field;

import java.math.BigDecimal;
import java.util.List;

/**
 * 字段定义：一个元数据编码一个字段，档案页、首诊问卷、评估问卷共用。
 *
 * @param legacy   档案页 / C 端生活方式整页提交时归它们管（不在此列的新字段，整页提交不会清掉）
 * @param external 值由专属组件实时维护（过敏、用药明细），问卷只展示不提交
 * @param virtual  由其他字段推导，读写走适配器（如一级亲属家族史 ↔ 家族史明细）
 */
public record FieldDef(
        String code,
        String label,
        FieldType type,
        String options,
        String unit,
        BigDecimal min,
        BigDecimal max,
        Integer maxLength,
        List<String> exclusive,
        boolean sensitive,
        boolean legacy,
        boolean readOnly,
        boolean external,
        boolean staffOnly,
        boolean virtual) {

    public FieldStore store() {
        return FieldStore.of(code);
    }

    public boolean choice() {
        return type == FieldType.ENUM || type == FieldType.MULTI;
    }
}
