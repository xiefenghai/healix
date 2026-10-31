package com.healix.core.program.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.program.domain.PeopleProgramEnrollment;
import com.healix.core.program.mapper.PeopleProgramEnrollmentMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProgramEnrollmentService {

    public static final String WEIGHT_LOSS = "WEIGHT_LOSS";
    public static final String STATUS_ONBOARDING = "ONBOARDING";
    public static final String STATUS_ACTIVE = "ACTIVE";

    private final PeopleProgramEnrollmentMapper enrollmentMapper;

    public List<PeopleProgramEnrollment> list(String tenantId, String orgId, String peopleId) {
        return enrollmentMapper.listByPeople(tenantId, orgId, peopleId);
    }

    public PeopleProgramEnrollment findOpen(
            String tenantId, String orgId, String peopleId, String programCode) {
        return enrollmentMapper.findOpen(tenantId, orgId, peopleId, programCode);
    }

    /** 选定项目：已有未结束入组则复用；否则建 ONBOARDING。 */
    @Transactional
    public PeopleProgramEnrollment ensureOnboarding(
            String tenantId, String orgId, String peopleId, String programCode, String staffId) {
        if (programCode == null || programCode.isBlank() || "NONE".equals(programCode)) {
            return null;
        }
        PeopleProgramEnrollment existing = enrollmentMapper.findOpen(tenantId, orgId, peopleId, programCode);
        if (existing != null) {
            return existing;
        }
        PeopleProgramEnrollment row = new PeopleProgramEnrollment();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setProgramCode(programCode);
        row.setStatus(STATUS_ONBOARDING);
        row.setEnrolledAt(LocalDateTime.now());
        row.setOwnerStaffId(staffId);
        EntityMeta.onCreate(row);
        enrollmentMapper.insert(row);
        return row;
    }

    @Transactional
    public PeopleProgramEnrollment activate(String enrollmentId, String intakeResponseId) {
        PeopleProgramEnrollment row = enrollmentMapper.findById(enrollmentId);
        if (row == null) {
            throw new BusinessException("入组不存在");
        }
        if (STATUS_ACTIVE.equals(row.getStatus())) {
            return row;
        }
        if (!STATUS_ONBOARDING.equals(row.getStatus())) {
            throw new BusinessException("入组状态不允许完成首诊: " + row.getStatus());
        }
        row.setStatus(STATUS_ACTIVE);
        row.setOnboardedAt(LocalDateTime.now());
        row.setIntakeResponseId(intakeResponseId);
        EntityMeta.onUpdate(row);
        enrollmentMapper.updateStatus(row);
        return row;
    }

    /** 参加减重且未 ACTIVE 时，不能生成减重方案。 */
    public boolean canGenerateWeightLossPlan(String tenantId, String orgId, String peopleId) {
        PeopleProgramEnrollment open = enrollmentMapper.findOpen(tenantId, orgId, peopleId, WEIGHT_LOSS);
        if (open == null) {
            return true; // 未参加项目，不受门槛约束
        }
        return STATUS_ACTIVE.equals(open.getStatus());
    }
}
