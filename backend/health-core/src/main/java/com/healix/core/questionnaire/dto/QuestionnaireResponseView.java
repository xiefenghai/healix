package com.healix.core.questionnaire.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuestionnaireResponseView {
    private String id;
    private String peopleId;
    private String orgId;
    private String questionnaireCode;
    private int questionnaireVersion;
    private String purpose;
    private String status;
    private String channel;
    private String programCode;
    private String enrollmentId;
    private String followupRecordId;
    private JsonNode prefill;
    private JsonNode answers;
    private JsonNode highlights;
    private JsonNode completeness;
    private JsonNode changes;
    private JsonNode snapshot;
    private LocalDateTime submittedAt;
    private List<ModuleView> modules;
    private String title;

    @Getter
    @Setter
    public static class ModuleView {
        private String code;
        private int version;
        private String title;
        private String script;
        /** 模块级显示条件（如按 careTracks 加载减重/慢病专属模块） */
        private JsonNode showIf;
        private List<ItemView> items;
    }

    @Getter
    @Setter
    public static class ItemView {
        private String field;
        private String label;
        private String level;
        private String type;
        private String unit;
        private String options;
        /** 数值题下限（来自 field-catalog） */
        private java.math.BigDecimal min;
        /** 数值题上限（来自 field-catalog） */
        private java.math.BigDecimal max;
        /** 多选互斥项，如 NONE / UNKNOWN（选中后清空其他） */
        private java.util.List<String> exclusive;
        private boolean sensitive;
        private JsonNode showIf;
        private JsonNode highlights;
    }
}
