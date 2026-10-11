package com.healix.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花 Long 超出 JS Number.MAX_SAFE_INTEGER 时序列化为字符串，避免前端精度丢失；
 * 小整数（如分页 total）仍输出数字。
 */
@Configuration
public class JacksonConfig {

    private static final long JS_MAX_SAFE = 9007199254740991L;

    @Bean
    Jackson2ObjectMapperBuilderCustomizer longAsSafeJsonCustomizer() {
        JsonSerializer<Long> serializer = new JsonSerializer<>() {
            @Override
            public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                if (value == null) {
                    gen.writeNull();
                } else if (value > JS_MAX_SAFE || value < -JS_MAX_SAFE) {
                    gen.writeString(Long.toString(value));
                } else {
                    gen.writeNumber(value);
                }
            }
        };
        return builder -> builder
                .serializerByType(Long.class, serializer)
                .serializerByType(Long.TYPE, serializer)
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
