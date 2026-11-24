package com.healix.config;

import com.healix.core.people.service.PatientMergeService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动幂等：按「本人手机号 + 姓名」合并跨租户重复 people。
 * <p>保留优先级：有 ACTIVE 入组 &gt; 已有 C 卡片 &gt; 创建更早。
 */
@Slf4j
@Component
@Order(85)
@RequiredArgsConstructor
public class PlatformPeopleDedupeRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PatientMergeService patientMergeService;

    @Value("${healix.people.dedupe-on-startup:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            int merged = dedupe();
            if (merged > 0) {
                log.warn("Platform people dedupe merged {} duplicate profile(s)", merged);
            }
        } catch (Exception e) {
            log.error("Platform people dedupe failed: {}", e.getMessage(), e);
        }
    }

    private int dedupe() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                """
                SELECT mobile, display_name
                FROM people_profile
                WHERE is_deleted = 0
                  AND merged_into_people_id IS NULL
                  AND mobile_owner = 'SELF'
                  AND mobile IS NOT NULL
                  AND mobile <> ''
                  AND display_name IS NOT NULL
                  AND display_name <> ''
                GROUP BY mobile, display_name
                HAVING COUNT(*) > 1
                LIMIT 200
                """);
        int merged = 0;
        for (Map<String, Object> g : groups) {
            String mobile = stringVal(g.get("mobile"));
            String name = stringVal(g.get("display_name"));
            if (!StringUtils.hasText(mobile) || !StringUtils.hasText(name)) {
                continue;
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT p.id AS id,
                           p.gmt_created AS gmt_created,
                           p.pk_id AS pk_id,
                           (SELECT COUNT(1) FROM people_org_membership m
                             WHERE m.people_id = p.id AND m.is_deleted = 0 AND m.status = 'ACTIVE') AS mem_cnt,
                           (SELECT COUNT(1) FROM account_patient c
                             WHERE c.people_id = p.id AND c.is_deleted = 0) AS card_cnt
                    FROM people_profile p
                    WHERE p.is_deleted = 0
                      AND p.merged_into_people_id IS NULL
                      AND p.mobile_owner = 'SELF'
                      AND p.mobile = ?
                      AND p.display_name = ?
                    """,
                    mobile,
                    name);
            if (rows.size() < 2) {
                continue;
            }
            rows.sort((a, b) -> {
                int mem = Long.compare(asLong(b.get("mem_cnt")), asLong(a.get("mem_cnt")));
                if (mem != 0) {
                    return mem;
                }
                int card = Long.compare(asLong(b.get("card_cnt")), asLong(a.get("card_cnt")));
                if (card != 0) {
                    return card;
                }
                return Long.compare(asLong(a.get("pk_id")), asLong(b.get("pk_id")));
            });
            String keep = stringVal(rows.get(0).get("id"));
            for (int i = 1; i < rows.size(); i++) {
                String source = stringVal(rows.get(i).get("id"));
                try {
                    patientMergeService.mergePlatform(
                            source, keep, "SYSTEM", "startup-dedupe mobile+name");
                    merged++;
                } catch (Exception ex) {
                    log.warn(
                            "Skip dedupe mobile={} name={} source={} keep={}: {}",
                            mobile,
                            name,
                            source,
                            keep,
                            ex.getMessage());
                }
            }
        }
        return merged;
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static long asLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0L;
        }
    }
}
