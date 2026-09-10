package com.example.gym.security;

import com.example.gym.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer access token on each request, populates the {@link SecurityContextHolder},
 * and sets the {@link TenantContext} from the token's tenant claim. Stateless: no server session.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                @SuppressWarnings("unchecked")
                List<String> authorities = claims.get("authorities", List.class);
                Set<GrantedAuthority> grantedAuthorities = new HashSet<>();
                if (authorities != null) {
                    authorities.forEach(a -> grantedAuthorities.add(new SimpleGrantedAuthority(a)));
                }

                Number uid = claims.get("uid", Number.class);
                Number tid = claims.get("tid", Number.class);
                Long tenantId = tid == null ? null : tid.longValue();
                AppUserPrincipal principal = new AppUserPrincipal(
                        uid == null ? null : uid.longValue(),
                        claims.get("pid", String.class),
                        tenantId,
                        claims.getSubject(),
                        null,
                        true,
                        true,
                        grantedAuthorities);

                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, grantedAuthorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                TenantContext.set(tenantId);
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid/expired token: leave the context unauthenticated; entry point handles 401.
                SecurityContextHolder.clearContext();
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
