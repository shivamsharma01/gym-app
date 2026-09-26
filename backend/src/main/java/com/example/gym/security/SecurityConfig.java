package com.example.gym.security;

import com.example.gym.common.web.RequestLoggingFilter;
import com.example.gym.security.ratelimit.RateLimitProperties;
import com.example.gym.security.ratelimit.RateLimitStore;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless Bearer access JWT + httpOnly refresh cookie (SameSite=Lax, path /api/v1/auth).
 * CSRF is disabled: SPA and API are same-site; refresh cookie is not readable by JS.
 * There is no server session.
 * All authorization is enforced server-side via method security ({@code @PreAuthorize}).
 *
 * <p>Actuator: {@code /actuator/health/**} is public (minimal details). {@code info},
 * {@code metrics}, and {@code threaddump} require {@code ROLE_SUPER_ADMIN} (proxied via nginx
 * for the platform SPA). Sensitive endpoints ({@code heapdump}, {@code env}, …) are denyAll.
 * Same application port; no separate management port.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, CorsProperties.class, RateLimitProperties.class})
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/health/**",
            "/error",
            // Device-gateway WSS + REST fallback authenticate with a per-gateway token
            // (or optional deployment shared token), not the user JWT filter.
            "/gateway",
            "/internal/gateway/**",
            "/api/v1/public/**",
            "/live"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PublicEndpointRateLimitFilter publicEndpointRateLimitFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CorsProperties corsProperties;
    private final RateLimitProperties rateLimitProperties;
    private final RateLimitStore rateLimitStore;
    private final JsonMapper jsonMapper;
    private final RequestLoggingFilter requestLoggingFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          PublicEndpointRateLimitFilter publicEndpointRateLimitFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          CorsProperties corsProperties,
                          RateLimitProperties rateLimitProperties,
                          RateLimitStore rateLimitStore,
                          JsonMapper jsonMapper,
                          RequestLoggingFilter requestLoggingFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.publicEndpointRateLimitFilter = publicEndpointRateLimitFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.corsProperties = corsProperties;
        this.rateLimitProperties = rateLimitProperties;
        this.rateLimitStore = rateLimitStore;
        this.jsonMapper = jsonMapper;
        this.requestLoggingFilter = requestLoggingFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthenticatedRateLimitFilter authenticatedRateLimitFilter =
                new AuthenticatedRateLimitFilter(rateLimitProperties, rateLimitStore, jsonMapper);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(
                                "/actuator/heapdump",
                                "/actuator/env",
                                "/actuator/configprops",
                                "/actuator/loggers",
                                "/actuator/shutdown").denyAll()
                        .requestMatchers("/actuator/**").hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'")))
                .addFilterBefore(publicEndpointRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(authenticatedRateLimitFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(requestLoggingFilter, AuthenticatedRateLimitFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Delegating encoder; default id is bcrypt. Supports transparent upgrades later.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Correlation-Id", "X-Request-Id", "X-Gym-Slug"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "X-Request-Id", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
