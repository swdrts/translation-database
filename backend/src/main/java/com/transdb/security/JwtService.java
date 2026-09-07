package com.transdb.security;

import com.transdb.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration expiry;

    public static final String DEV_DEFAULT_SECRET = "dev-only-secret-key-change-me-32bytes!";

    @Autowired
    public JwtService(@Value("${transdb.jwt.secret}") String secret,
                      @Value("${transdb.jwt.expiry-hours}") long expiryHours,
                      @Value("${TRANSDB_PROFILE:dev}") String profile) {
        this(secret, Duration.ofHours(expiryHours));
        ensureNotDevSecretInProd(secret, profile);
    }

    JwtService(String secret, Duration expiry) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = expiry;
    }

    private static void ensureNotDevSecretInProd(String secret, String profile) {
        if ("prod".equals(profile) && DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "生产环境必须通过环境变量 TRANSDB_JWT_SECRET 配置强随机密钥（≥32 字节）");
        }
    }

    public String generate(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.username())
                .claim("uid", user.id())
                .claim("displayName", user.displayName())
                .claim("role", user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    public LoginUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new LoginUser(
                claims.get("uid", Long.class),
                claims.getSubject(),
                claims.get("displayName", String.class),
                Role.valueOf(claims.get("role", String.class)));
    }
}
