package com.healix.core.persona.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import lombok.Data;

/** B 端用户画像 GET/写接口响应。 */
@Data
public class PersonaView {
    private String peopleId;
    private boolean persisted;
    private String status;
    private JsonNode dimensions;
    private JsonNode narrative;
    /** 紧凑条标题：有叙事用 headline，否则规则灰字。 */
    private String displayHeadline;
    private String sourceFingerprint;
    private LocalDateTime generatedAt;
    private LocalDateTime dimensionsAt;
    /** 刷新时若因整包锁跳过叙事，为 true。 */
    private Boolean narrativeSkippedLocked;
}
