package com.test.engine.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;

/**
 * SPA fallback via the error path. Static resources and API routes are
 * handled normally; only unmatched client-side routes (404, non-/api) are
 * forwarded to index.html so Vue Router history mode works when the frontend
 * dist is bundled into the jar.
 */
@Controller
public class SpaErrorController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttr instanceof Integer i ? i : 500;
        String uri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        if (status == 404 && uri != null && !uri.startsWith("/api/")) {
            // reset to 200 before forwarding, otherwise the original 404
            // status leaks through to the SPA response
            response.setStatus(HttpServletResponse.SC_OK);
            // never cache the SPA shell: after a deploy the browser must
            // re-fetch index.html to pick up the new hashed asset URLs
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            return "forward:/index.html";
        }
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"request failed\"}");
        return null;
    }
}
