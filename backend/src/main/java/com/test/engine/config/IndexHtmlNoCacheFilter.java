package com.test.engine.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * index.html must never be cached: after a deploy the browser has to re-fetch
 * it so the new hashed asset URLs are picked up, instead of a stale page that
 * still references old chunks (user had to hard-refresh after each release).
 * Only "/" and "/index.html" are affected - hashed assets under /assets/ keep
 * their 7-day browser cache (StaticCacheConfig).
 */
@Component
public class IndexHtmlNoCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
        }
        filterChain.doFilter(request, response);
    }
}
