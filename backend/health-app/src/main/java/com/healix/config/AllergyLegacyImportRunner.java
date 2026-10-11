package com.healix.config;

import com.healix.core.allergy.service.AllergyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动时把旧版「既往史 → 过敏史」条目迁移到过敏史表；已迁移的患者自动跳过，失败只记日志。 */
@Slf4j
@Component
@Order(75)
@RequiredArgsConstructor
public class AllergyLegacyImportRunner implements ApplicationRunner {

    private final AllergyService allergyService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = allergyService.importLegacyPastHistory();
            if (n > 0) {
                log.info("Imported legacy past-history allergies for {} patients", n);
            }
        } catch (Exception e) {
            log.error("Legacy allergy import failed: {}", e.getMessage(), e);
        }
    }
}
