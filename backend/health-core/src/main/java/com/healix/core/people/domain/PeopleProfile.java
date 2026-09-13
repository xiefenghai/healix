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
    /** 联系手机号 */
    private String mobile;
    /** 家庭住址 */
    private String address;
    /** 文化程度 code，见 EducationLevelEnum */
    private String educationLevel;
    /** 婚姻状况 code，见 MaritalStatusEnum */
    private String maritalStatus;
    /** 职业（自由文本） */
    private String occupation;
    private String allergensJson;
    private String chronicTagsJson;
    private String emergencyContactJson;
    /** 已合并到的目标 people_id；非空表示该档案是合并留痕，不再对外使用 */
    private String mergedIntoPeopleId;
}
