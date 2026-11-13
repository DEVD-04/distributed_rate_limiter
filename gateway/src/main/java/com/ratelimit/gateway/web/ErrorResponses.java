package com.ratelimit.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes the gateway's own JSON error bodies, always carrying the request id for correlation. */
@Component
public class ErrorResponses {

    private final ObjectMapper json;

    public ErrorResponses(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String error,
            String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("request_id", RequestContext.requestId(request));

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(body));
    }
}
