package com.gpr.payroll.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** This app's audience. Tokens minted for a different app are rejected. */
    private static final String APP_AUD = "workos";

    private final JwtService jwtService;
    private final PermissionLoader permissionLoader;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractAccessToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.extractAllClaims(token);

                // Lenient aud check: reject only if aud is present and not ours. Tokens minted before
                // multi-app support (no aud) are still accepted during the transition.
                Set<String> aud = claims.getAudience();
                if (aud != null && !aud.isEmpty() && !aud.contains(APP_AUD)) {
                    throw new IllegalStateException("Token audience " + aud + " not valid for app '" + APP_AUD + "'");
                }

                String email = claims.get("email", String.class);
                String role = claims.get("role", String.class);

                Object rawId = claims.get("userRoleId");
                Long userRoleId = rawId instanceof Number ? ((Number) rawId).longValue() : null;

                // Scopes @TenantId entities (payslips, runs) to the caller's company for this
                // request. The claim was always present — this service just never read it, so
                // payroll data was global.
                Object rawCompany = claims.get("companyId");
                Long companyId = rawCompany instanceof Number ? ((Number) rawCompany).longValue() : null;
                boolean superAdmin = Boolean.TRUE.equals(claims.get("super_admin", Boolean.class));
                Long identityId = null;
                try {
                    identityId = claims.getSubject() == null ? null : Long.valueOf(claims.getSubject());
                } catch (NumberFormatException ignored) {
                    // Older tokens carried a non-numeric subject; identity-scoped checks fall back
                    // to the email instead.
                }
                TenantContext.set(companyId, superAdmin, identityId);

                List<SimpleGrantedAuthority> authorities = new ArrayList<>(permissionLoader.loadByUserRoleId(userRoleId));
                if ("ADMIN".equalsIgnoreCase(role)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }

                UserDetails userDetails = User.withUsername(email)
                        .password("")
                        .authorities(authorities)
                        .build();

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } catch (Exception e) {
                log.warn("JWT authentication failed for request [{}]: {}", request.getRequestURI(), e.getMessage());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Threads are pooled — a leaked context would scope the next request to the wrong company.
            TenantContext.clear();
        }
    }

    private String extractAccessToken(HttpServletRequest request) {
        String cookieToken = extractCookieToken(request);
        if (cookieToken != null && jwtService.validateToken(cookieToken)) return cookieToken;
        String bearerToken = extractBearerToken(request);
        if (bearerToken != null && jwtService.validateToken(bearerToken)) return bearerToken;
        return null;
    }

    private String extractCookieToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> "access_token".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
