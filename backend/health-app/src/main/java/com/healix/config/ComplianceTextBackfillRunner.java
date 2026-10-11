package com.healix.config;

import com.healix.core.compliance.ComplianceText;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时把历史计划里的诊疗用语（如「按医嘱服药」）改成健康管理表述；规则同 {@link ComplianceText}。
 * 只处理仍命中的行，替换后不再命中，可重复执行。
 */
@Slf4j
@Component
@Order(76)
@RequiredArgsConstructor
public class ComplianceTextBackfillRunner implements ApplicationRunner {

    /** 表 → 患者可见的文本 / JSON 列 */
    private static final Map<String, List<String>> TARGETS = Map.of(
            "care_plan_task", List.of("title"),
            "care_plan", List.of("title", "goal_summary"),
            "care_plan_version", List.of("title", "execution_json", "exercise_json", "diet_json"),
            "care_plan_draft", List.of("execution_json", "exercise_json", "diet_json"));
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        TARGETS.forEach((table, columns) -> {
            for (String column : columns) {
                try {
                    int n = backfill(table, column);
                    if (n > 0) {
                        log.info("Compliance wording backfill {}.{}: {} rows", table, column, n);
                    }
                } catch (Exception e) {
                    log.error("Compliance wording backfill {}.{} failed: {}", table, column, e.getMessage());
                }
            }
        });
    }

    private int backfill(String table, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pk_id, " + column + " AS v FROM " + table + " WHERE " + column + " REGEXP ?",
                ComplianceText.MYSQL_REGEXP);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String before = Objects.toString(row.get("v"), null);
            String after = ComplianceText.filter(before);
            if (after != null && !after.equals(before)) {
                updated += jdbcTemplate.update(
                        "UPDATE " + table + " SET " + column + " = ? WHERE pk_id = ?", after, row.get("pk_id"));
            }
        }
        return updated;
    }
}
