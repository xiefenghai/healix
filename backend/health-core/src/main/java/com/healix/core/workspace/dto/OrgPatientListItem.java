package com.healix.core.workspace.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class OrgPatientListItem {
    private String peopleId;
    private String displayName;
    private String gender;
    private LocalDate birthday;
    private String identityMask;
    private String identityType;
    private String careTeamId;
    private String careTeamName;
    private LocalDateTime joinedAt;
    /**
     * 是否已有 C 端就诊人卡片关联。
     * <p>勿使用 {@code cLinked} 命名：Jackson 会把 {@code getCLinked} 序列化成 {@code clinked}，
     * 导致前端读不到、界面一直显示「未关联」。
     */
    private Boolean clientLinked;
    /** 关联的 C 端就诊人卡片数量（跨账号合计） */
    private Integer clientCardCount;
    /** 联系手机号 */
    private String mobile;
    /** 家庭住址 */
    private String address;
    /** 文化程度 code */
    private String educationLevel;
    /** 婚姻状况 code */
    private String maritalStatus;
    /** 职业 */
    private String occupation;
}
