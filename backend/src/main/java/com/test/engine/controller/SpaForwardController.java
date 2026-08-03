package com.test.engine.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * SPA fallback: forwards client-side routes (non-API, non-file paths) to
 * index.html so that Vue Router history mode works when the frontend dist is
 * bundled into the jar. API routes keep their normal 404 handling.
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = {"/", "/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String forward(HttpServletRequest request) throws NoResourceFoundException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            throw new NoResourceFoundException(HttpMethod.GET, uri);
        }
        return "forward:/index.html";
    }
}
