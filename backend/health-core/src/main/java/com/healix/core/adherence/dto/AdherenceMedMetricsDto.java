package com.healix.core.adherence.dto;

import lombok.Data;

@Data
public class AdherenceMedMetricsDto {
    /** 当日在用药品种数 */
    private int activeCount;
    /** 当日已打卡（至少服过一次）的药品种数 */
    private int takenCount;
    /** 当日应服次数：按频次展开（BID=2、TID=3），PRN 不计 */
    private int dueDoseCount;
    /** 当日已服次数：按药封顶到该药应服次数 */
    private int takenDoseCount;
    /** 按次判定：应服 &gt; 0 且已服 &lt; 应服 */
    private boolean todayIncomplete;
}
