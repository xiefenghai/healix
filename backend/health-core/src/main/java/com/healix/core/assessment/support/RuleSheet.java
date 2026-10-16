package com.healix.core.assessment.support;

import java.util.List;

/** 规则说明（规则单一来源：前端通用渲染，不再在前端维护规则副本）。 */
public record RuleSheet(String summary, List<Section> sections, List<String> notes) {

    /** 一节规则：可以是表格（headers + rows），也可以只有条目（bullets）。 */
    public record Section(String title, List<String> headers, List<List<String>> rows, List<String> bullets) {

        public static Section table(String title, List<String> headers, List<List<String>> rows) {
            return new Section(title, headers, rows, List.of());
        }

        public static Section bullets(String title, List<String> bullets) {
            return new Section(title, List.of(), List.of(), bullets);
        }
    }
}
