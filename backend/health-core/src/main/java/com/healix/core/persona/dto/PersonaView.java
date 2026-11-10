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
    /** 叙事生成时绑定的维度指纹（库存）。 */
    private String narrativeFingerprint;
    private LocalDateTime generatedAt;
    private LocalDateTime dimensionsAt;
    /** 刷新时若因整包锁跳过叙事，为 true。 */
    private Boolean narrativeSkippedLocked;
    /**
     * 活维度指纹与叙事指纹不一致：血压/病种/过敏等临床数据已变，建议用户点「重新生成」。
     * GET 只提示，不自动调 LLM。
     */
    private Boolean dimensionsStale;
    /** generate/refresh 时指纹命中缓存，未调用 LLM。 */
    private Boolean llmSkipped;
}
