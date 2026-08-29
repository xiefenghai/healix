package com.healix.security;

import com.healix.core.portal.enums.PortalEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expirationSeconds;

    public JwtTokenProvider(
            @Value("${healix.security.jwt-secret}") String secret,
            @Value("${healix.security.jwt-expiration-seconds:86400}") long expirationSeconds) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public String createPatientToken(String accountId, String patientId, String homeTenantId) {
        var builder = base(accountId, PortalEnum.C.code()).claim("patientId", patientId);
        if (homeTenantId != null) {
            builder.claim("homeTenantId", homeTenantId);
        }
        return builder.compact();
    }

    public String createStaffToken(
            String accountId, String staffId, String tenantId, String currentOrgId, Collection<String> roles) {
        return base(accountId, PortalEnum.B.code())
                .claim("staffId", staffId)
                .claim("tenantId", tenantId)
                .claim("currentOrgId", currentOrgId)
                .claim("roles", roles == null ? List.of() : List.copyOf(roles))
                .compact();
    }

    public String createOpsToken(String accountId, String roleCode) {
        return base(accountId, PortalEnum.OPS.code()).claim("roleCode", roleCode).compact();
    }

    private io.jsonwebtoken.JwtBuilder base(String accountId, String aud) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(accountId))
                .audience()
                .add(aud)
                .and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(secretKey);
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }

    public String requireAudience(Claims claims) {
        Set<String> audiences = claims.getAudience();
        if (audiences == null || audiences.isEmpty()) {
            throw new IllegalArgumentException("missing aud");
        }
        return audiences.iterator().next();
    }

    @SuppressWarnings("unchecked")
    public Set<String> roles(Claims claims) {
        Object raw = claims.get("roles");
        if (raw instanceof Collection<?> col) {
            return col.stream().map(String::valueOf).collect(Collectors.toSet());
        }
        return Set.of();
    }
}
