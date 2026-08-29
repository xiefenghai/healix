package com.healix.core.config;

import com.healix.common.util.IdCardCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdCardCryptoConfig {

    @Bean
    public IdCardCrypto idCardCrypto(
            @Value("${healix.security.id-card-aes-key:healix-dev-idcard-aes-key!!}") String aesKey,
            @Value("${healix.security.id-card-pepper:healix-dev-idcard-pepper}") String pepper) {
        return new IdCardCrypto(aesKey, pepper);
    }
}
