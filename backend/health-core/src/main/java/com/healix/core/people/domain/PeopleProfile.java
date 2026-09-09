package com.healix.core.people.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 患者/自然人基础信息（租户隔离） */
@Getter
@Setter
public class PeopleProfile extends BaseEntity {
    private String tenantId;
    /** 关联 people_account.id；可空=尚未激活 C 登录 */
    private String accountId;
    private String displayName;
    private String gender;
    private LocalDate birthday;
    private String namePinyin;
    private String allergensJson;
    private String chronicTagsJson;
    private String emergencyContactJson;
    /** 已合并到的目标 people_id；非空表示该档案是合并留痕，不再对外使用 */
    private String mergedIntoPeopleId;
}
