package com.test.engine.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless JWT security for the REST API. Auth endpoints are public; all
 * other /api/** routes require a valid token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /** Dev-only switch: the H2 console stays protected (401) by default. */
    @Value("${app.h2-console-enabled:false}")
    private boolean h2ConsoleEnabled;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // H2 console is NOT public by default: only a dev
                        // build with app.h2-console-enabled=true may open it
                        .requestMatchers(r -> h2ConsoleEnabled && r.getRequestURI().startsWith("/h2-console")).permitAll()
                        // PVP SSE refresh channel: signal-only (no battle data),
                        // so the browser's EventSource can subscribe without a
                        // Bearer header; real state always comes from the
                        // authenticated combat API
                        .requestMatchers("/api/pvp/events/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/packs/**").permitAll()
                        // SPA pages and static assets (everything outside /api) are public;
                        // the frontend router guards private views client-side.
                        .requestMatchers(r -> !r.getRequestURI().startsWith("/api/")).permitAll()
                        // OP-only: account & permission management
                        .requestMatchers("/api/admin/users/**").hasRole("OP")
                        // ADMIN or OP: content design (card packs, enemies, characters)
                        .requestMatchers("/api/design/**").hasAnyRole("ADMIN", "OP")
                        .anyRequest().authenticated())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        // let the browser cache static art; the default
                        // no-cache header made curtain/dash images
                        // re-download on every round transition
                        .cacheControl(cache -> cache.disable()))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "未认证")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
