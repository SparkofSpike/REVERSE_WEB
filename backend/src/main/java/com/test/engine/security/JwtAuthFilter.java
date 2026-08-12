package com.test.engine.security;

import com.test.engine.config.OpConfig;
import com.test.engine.entity.User;
import com.test.engine.enums.UserRole;
import com.test.engine.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Authorization: Bearer header, resolves the account from the DB and
 * seeds the security context with role authorities. Disabled accounts get no
 * authentication, so disabling a user kills their outstanding tokens at once.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final OpConfig opConfig;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository, OpConfig opConfig) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.opConfig = opConfig;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtService.isValid(token)) {
                String username = jwtService.extractUsername(token);
                User user = userRepository.findByUsername(username).orElse(null);
                if (user != null && user.isEnabled()) {
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                    // OP is configuration-driven and always implies ADMIN.
                    if (user.getRole() == UserRole.ADMIN || opConfig.isOp(user.getId())) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    }
                    if (opConfig.isOp(user.getId())) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_OP"));
                    }
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(username, null, authorities);
                    auth.setDetails(user.getId());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
