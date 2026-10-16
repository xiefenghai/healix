package com.healix.config;

import com.healix.core.medication.service.MedicationSlotMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动时把历史 OTHER 时段打卡迁到推断时段 / PRN；无待迁移数据时为空跑，失败只记日志。 */
@Slf4j
@Component
@Order(77)
@RequiredArgsConstructor
public class MedicationSlotMigrationRunner implements ApplicationRunner {

    private final MedicationSlotMigrationService migrationService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = migrationService.migrateLegacyOther();
            if (n > 0) {
                log.info("Migrated {} legacy OTHER medication intakes", n);
            }
        } catch (Exception e) {
            log.error("Medication slot migration failed: {}", e.getMessage(), e);
        }
    }
}
