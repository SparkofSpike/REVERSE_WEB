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

/** Stateless JWT security for API routes. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /** Enables public H2 console access for local development. */
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
                        // Keep the H2 console private unless explicitly enabled.
                        .requestMatchers(r -> h2ConsoleEnabled && r.getRequestURI().startsWith("/h2-console")).permitAll()
                        // EventSource cannot send bearer headers; this channel only signals refreshes.
                        .requestMatchers("/api/pvp/events/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/packs/**").permitAll()
                        // Avatar images are public because <img> cannot send bearer headers.
                        .requestMatchers("/api/avatars/**").permitAll()
                        .requestMatchers(r -> !r.getRequestURI().startsWith("/api/")
                                && !r.getRequestURI().startsWith("/h2-console")).permitAll()
                        .requestMatchers("/api/admin/users/**").hasRole("OP")
                        .requestMatchers("/api/design/**").hasAnyRole("ADMIN", "OP")
                        .anyRequest().authenticated())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        // Allow caching of static assets.
                        .cacheControl(cache -> cache.disable()))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "未认证")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
