package com.healix.core.worktask.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.healix.core.worktask.catalog.WorkspaceTaskType;
import org.junit.jupiter.api.Test;

class WorkspaceTaskLabelsTest {

    @Test
    void followUpUsesFollowupTypeWhenPresent() {
        assertEquals(
                "入组/首诊随访",
                WorkspaceTaskLabels.of(WorkspaceTaskType.FOLLOW_UP, "{\"followupType\":\"ONBOARDING\"}"));
        assertEquals(
                "常规健康回访",
                WorkspaceTaskLabels.of(WorkspaceTaskType.FOLLOW_UP, "{\"followupType\":\"ROUTINE\"}"));
    }

    @Test
    void followUpFallsBackWithoutSubtype() {
        assertEquals("定期随访", WorkspaceTaskLabels.of(WorkspaceTaskType.FOLLOW_UP, null));
        assertEquals("定期随访", WorkspaceTaskLabels.of(WorkspaceTaskType.FOLLOW_UP, "{}"));
    }

    @Test
    void planCreateRenewLabel() {
        assertEquals(
                WorkspaceTaskLabels.PLAN_RENEW_LABEL,
                WorkspaceTaskLabels.of(WorkspaceTaskType.PLAN_CREATE, "{\"renew\":true}"));
        assertEquals("制定方案", WorkspaceTaskLabels.of(WorkspaceTaskType.PLAN_CREATE, "{}"));
    }
}
