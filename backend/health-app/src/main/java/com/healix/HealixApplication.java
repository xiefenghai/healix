package com.healix;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.healix")
@MapperScan("com.healix")
public class HealixApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealixApplication.class, args);
    }
}
