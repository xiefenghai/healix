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
        private boolean sensitive;
        private JsonNode showIf;
        private JsonNode highlights;
    }
}
