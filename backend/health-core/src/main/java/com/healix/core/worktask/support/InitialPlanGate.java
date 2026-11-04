package com.healix.core.worktask.support;

import com.healix.core.worktask.catalog.WorkspaceTaskType;
import java.time.LocalDate;

/**
 * 制定方案何时可以开：首诊已办结，或首诊已取消，或首诊/入组已超过随访时限。
 * 时限内仍有未办结首诊时不开，避免档案还没补齐就要求出方案。
 */
public final class InitialPlanGate {

    private InitialPlanGate() {}

    public static boolean ready(String onboardingStatus, LocalDate dueDay, LocalDate joinedDay, LocalDate scanDay) {
        if (scanDay == null) {
            return false;
        }
        if ("DONE".equals(onboardingStatus) || "CANCELLED".equals(onboardingStatus)) {
            return true;
        }
        if ("OPEN".equals(onboardingStatus)) {
            return dueDay != null && dueDay.isBefore(scanDay);
        }
        if (joinedDay == null) {
            return false;
        }
        int slaDays = Math.max(0, WorkspaceTaskType.FOLLOW_UP.dueDaysInclusive() - 1);
        return joinedDay.plusDays(slaDays).isBefore(scanDay);
    }

    public static String summary(String onboardingStatus) {
        if ("DONE".equals(onboardingStatus)) {
            return "首诊已完成，请制定管理方案";
        }
        return "首诊超时未办结，请制定管理方案";
    }
}
