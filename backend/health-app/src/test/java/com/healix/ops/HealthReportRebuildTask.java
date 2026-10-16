package com.healix.ops;

import com.healix.HealixApplication;
import com.healix.core.report.service.HealthReportService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 运维：把某患者的全部管理报告按当前 schema 重算快照（保留状态与寄语）。
 * <pre>mvn -o test -pl health-app -am -Dtest=HealthReportRebuildTask -Dsurefire.failIfNoSpecifiedTests=false -Dreport.rebuild.peopleId=xxx</pre>
 */
@EnabledIfSystemProperty(named = "report.rebuild.peopleId", matches = ".+")
@SpringBootTest(
        classes = HealixApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "healix.job.tick-ms=86400000")
class HealthReportRebuildTask {

    @Autowired
    private HealthReportService healthReportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rebuild() {
        String peopleId = System.getProperty("report.rebuild.peopleId");
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM health_report WHERE people_id = ? AND is_deleted = 0 ORDER BY period_start",
                String.class,
                peopleId);
        for (String id : ids) {
            try {
                healthReportService.rebuildContent(id);
                System.out.println("REBUILD OK " + id);
            } catch (Exception e) {
                System.out.println("REBUILD FAIL " + id + " " + e.getMessage());
            }
        }
    }
}
