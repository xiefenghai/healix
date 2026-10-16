package com.healix.core.reward.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 计分源记录（患者本人在 C 端完成的一条行为）。
 *
 * <p>{@code occurredAt} 仅体征有值（测量时间）；{@code groupId}/{@code sub} 用于体征合并与去重。
 */
@Getter
@Setter
@NoArgsConstructor
public class RewardSourceEvent {
    private String bizType;
    private String ref;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
    private String groupId;
    private String sub;
    private String label;
    /** 体征数值（合理性校验用） */
    private BigDecimal metricValue;

    public RewardSourceEvent(String bizType, String ref, LocalDateTime createdAt, String label) {
        this.bizType = bizType;
        this.ref = ref;
        this.createdAt = createdAt;
        this.label = label;
    }
}
