package com.healix.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healix.common.result.ApiResult;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE / SseEmitter 会触发 ASYNC 二次分发，内部请求不带 JWT，须放行
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.FORWARD)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // register/login/activate/bootstrap 均无需登录
                        .requestMatchers("/api/c/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/c/v1/me/archive/lifestyle").hasAnyAuthority("ROLE_C", "ROLE_c")
                        .requestMatchers(HttpMethod.POST, "/api/b/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/ops/v1/auth/**").permitAll()
                        .requestMatchers("/api/c/v1/**").hasAnyAuthority("ROLE_C", "ROLE_c")
                        .requestMatchers("/api/b/v1/**").hasAuthority("ROLE_B")
                        .requestMatchers("/api/ops/v1/**").hasAuthority("ROLE_OPS")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeAuthJson(response, 401, "登录信息已过期"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeAuthJson(response, 403, "登录信息已过期或无权限")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeAuthJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResult.fail(status, message));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
