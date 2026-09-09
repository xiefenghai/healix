package com.healix;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.healix")
@MapperScan(value = "com.healix", annotationClass = Mapper.class)
public class HealixApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealixApplication.class, args);
    }
}
