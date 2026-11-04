package com.healix.core.patientcard.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** C 端关联申请或资料修改申请。 */
@Getter
@Setter
public class PeopleLinkRequest extends BaseEntity {
    public static final String KIND_LINK = "LINK";
    public static final String KIND_DEMOGRAPHIC = "DEMOGRAPHIC";
    public static final String OPEN = "OPEN";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String EXPIRED = "EXPIRED";
    public static final String DISMISSED = "DISMISSED";

    private String tenantId;
    private String accountId;
    private String kind;
    private String status;
    private String mobile;
    private String sourcePeopleId;
    private String targetPeopleId;
    private String orgId;
    private String taskId;
    private String displayName;
    private String gender;
    private LocalDate birthday;
    private String relation;
    private String proposedJson;
    private LocalDateTime expireAt;
    private LocalDateTime resolvedAt;
}
