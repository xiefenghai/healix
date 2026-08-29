package com.healix.config;

import com.healix.core.identity.service.IdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapRunner implements ApplicationRunner {

    private final IdentityService identityService;

    @Value("${healix.bootstrap.ops-username:opsadmin}")
    private String opsUsername;

    @Value("${healix.bootstrap.ops-password:OpsAdmin123!}")
    private String opsPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (identityService.opsAccountCount() == 0) {
            identityService.ensureBootstrapOps(opsUsername, opsPassword);
            log.warn("Bootstrapped default Ops account username={}", opsUsername);
        }
    }
}
