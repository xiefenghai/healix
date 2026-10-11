package com.healix.core.worktask.catalog;

/**
 * 工作台任务优先级（写入 {@code workspace_task.priority}）。
 * weight 越大越优先，供驾驶舱 / 列表排序。
 */
public enum WorkspaceTaskPriority {
    HIGH(3),
    MEDIUM(2),
    LOW(1);

    private final int weight;

    WorkspaceTaskPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public static WorkspaceTaskPriority require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知任务优先级: " + value);
        }
    }

    /** 兼容历史 NORMAL 等非标值，未知时回落 MEDIUM。 */
    public static WorkspaceTaskPriority fromStored(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        String v = value.trim().toUpperCase();
        if ("NORMAL".equals(v)) {
            return MEDIUM;
        }
        try {
            return valueOf(v);
        } catch (Exception e) {
            return MEDIUM;
        }
    }

    public static WorkspaceTaskPriority max(WorkspaceTaskPriority a, WorkspaceTaskPriority b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.weight >= b.weight ? a : b;
    }
}
