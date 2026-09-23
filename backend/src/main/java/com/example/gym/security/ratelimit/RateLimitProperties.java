package com.example.gym.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private int loginPerMinute = 20;
    private int refreshPerMinute = 30;
    private int enquiryPerMinute = 10;
    private int passwordChangePerMinute = 10;
    private int authenticatedPerMinute = 300;
    private int reportsPerMinute = 30;
    private int retryAfterSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLoginPerMinute() {
        return loginPerMinute;
    }

    public void setLoginPerMinute(int loginPerMinute) {
        this.loginPerMinute = loginPerMinute;
    }

    public int getRefreshPerMinute() {
        return refreshPerMinute;
    }

    public void setRefreshPerMinute(int refreshPerMinute) {
        this.refreshPerMinute = refreshPerMinute;
    }

    public int getEnquiryPerMinute() {
        return enquiryPerMinute;
    }

    public void setEnquiryPerMinute(int enquiryPerMinute) {
        this.enquiryPerMinute = enquiryPerMinute;
    }

    public int getPasswordChangePerMinute() {
        return passwordChangePerMinute;
    }

    public void setPasswordChangePerMinute(int passwordChangePerMinute) {
        this.passwordChangePerMinute = passwordChangePerMinute;
    }

    public int getAuthenticatedPerMinute() {
        return authenticatedPerMinute;
    }

    public void setAuthenticatedPerMinute(int authenticatedPerMinute) {
        this.authenticatedPerMinute = authenticatedPerMinute;
    }

    public int getReportsPerMinute() {
        return reportsPerMinute;
    }

    public void setReportsPerMinute(int reportsPerMinute) {
        this.reportsPerMinute = reportsPerMinute;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public void setRetryAfterSeconds(int retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
