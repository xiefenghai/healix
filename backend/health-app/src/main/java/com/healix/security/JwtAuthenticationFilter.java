package com.healix.security;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.core.portal.enums.PortalEnum;
import io.jsonwebtoken.Claims;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                Claims claims = jwtTokenProvider.parse(token);
                String aud = jwtTokenProvider.requireAudience(claims);
                String accountId = claims.getSubject();

                RequestContext ctx = new RequestContext();
                ctx.setPortal(aud);
                ctx.setAccountId(accountId);

                Set<String> authorities = Set.of("ROLE_" + aud.toUpperCase());
                if (PortalEnum.C.matchesCode(aud)) {
                    ctx.setPatientId(asString(claims.get("patientId")));
                    ctx.setActivePatientCardId(asString(claims.get("activePatientCardId")));
                    ctx.setHomeTenantId(asString(claims.get("homeTenantId")));
                    ctx.setTenantId(ctx.getHomeTenantId());
                } else if (PortalEnum.B.matchesCode(aud)) {
                    ctx.setStaffId(asString(claims.get("staffId")));
                    ctx.setTenantId(asString(claims.get("tenantId")));
                    ctx.setCurrentOrgId(asString(claims.get("currentOrgId")));
                    Set<String> roles = jwtTokenProvider.roles(claims);
                    ctx.setRoles(roles);
                    authorities = roles.stream().map(r -> "ROLE_" + r).collect(Collectors.toSet());
                    authorities = new java.util.HashSet<>(authorities);
                    authorities.add("ROLE_B");
                } else if (PortalEnum.OPS.matchesCode(aud)) {
                    String roleCode = String.valueOf(claims.get("roleCode"));
                    ctx.setRoles(Set.of(roleCode));
                    authorities = Set.of("ROLE_OPS", "ROLE_" + roleCode);
                }

                RequestContextHolder.set(ctx);
                var auth = new UsernamePasswordAuthenticationToken(
                        accountId,
                        null,
                        authorities.stream().map(SimpleGrantedAuthority::new).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            filterChain.doFilter(request, response);
        } finally {
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) {
                        clearContext();
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {
                        clearContext();
                    }

                    @Override
                    public void onError(AsyncEvent event) {
                        clearContext();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                        // no-op
                    }
                });
            } else {
                clearContext();
            }
        }
    }

    private static void clearContext() {
        RequestContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    /** 在异步线程中延续当前请求的 Security / Request 上下文（SSE 流式任务使用）。 */
    public static Runnable wrapAsync(Runnable task) {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        RequestContext requestContext = RequestContextHolder.get();
        Runnable wrapped = new DelegatingSecurityContextRunnable(task, securityContext);
        return () -> {
            RequestContext previous = RequestContextHolder.get();
            try {
                if (requestContext != null) {
                    RequestContextHolder.set(requestContext);
                }
                wrapped.run();
            } finally {
                if (previous != null) {
                    RequestContextHolder.set(previous);
                } else {
                    RequestContextHolder.clear();
                }
            }
        };
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return "null".equals(s) ? null : s;
    }
}
