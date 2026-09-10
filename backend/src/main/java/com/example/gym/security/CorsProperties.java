package com.example.gym.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** CORS configuration (bound from {@code app.cors.*}). */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** Explicit allowed origins (no wildcards when credentials are used). */
    private List<String> allowedOrigins = List.of("http://localhost:5173");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
