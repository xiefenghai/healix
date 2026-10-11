package com.healix.core.allergy.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 过敏史条目，字段对齐 FHIR AllergyIntolerance。 */
@Getter
@Setter
public class PeopleAllergy extends BaseEntity {
    private String tenantId;
    private String peopleId;
    /** 见 {@link com.healix.core.allergy.enums.AllergyCategoryEnum} */
    private String category;
    /** 见 {@link com.healix.core.allergy.support.AllergenCatalog}；自由录入为空 */
    private String allergenCode;
    private String allergenName;
    /** ALLERGY / INTOLERANCE */
    private String allergyType;
    private String reactionsJson;
    private String reactionNote;
    private String severity;
    private Integer highRisk;
    private String clinicalStatus;
    private String verification;
    private String onsetText;
    private LocalDate lastOccurrence;
    /** SELF / STAFF / IMPORT */
    private String source;
    private String note;
    private String verifiedByStaffId;
    private LocalDateTime verifiedAt;
    private String createdByType;
    private String createdById;
    private String updatedByType;
    private String updatedById;
}
