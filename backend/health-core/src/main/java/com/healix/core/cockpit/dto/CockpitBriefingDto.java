package com.healix.core.cockpit.dto;

import java.util.ArrayList;
import java.util.List;
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
    }
}
