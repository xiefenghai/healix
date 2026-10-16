package com.healix.core.cockpit.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** 驾驶舱开场简报（LLM 或模板降级）。 */
@Data
public class CockpitBriefingDto {
    private String text;
    private boolean fromLlm;
    /** 命中同 staff+org 自然日 Redis 缓存 */
    private boolean cached;
    /** 降级说明等，前端可不展示 */
    private String note;
    private List<BriefingAction> actions = new ArrayList<>();

    @Data
    public static class BriefingAction {
        private String peopleId;
        private String label;
        /** 缺省 FOCUS_PATIENT；另有 CALL_API / TRIGGER_CAPABILITY */
        private String type;
        /** CALL_API 的 API 键或 TRIGGER_CAPABILITY 的能力码 */
        private String path;
        private Map<String, Object> payload;
    }
}
