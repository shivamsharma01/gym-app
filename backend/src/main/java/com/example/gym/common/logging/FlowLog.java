package com.example.gym.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Business-flow logs under {@code gym.flow.<name>} so one flow can be turned up or down without
 * changing code. Never pass passwords, tokens, or photo bytes.
 *
 * <p>Examples ({@code application.yml} or {@code LOGGING_LEVEL_GYM_FLOW}):
 * <ul>
 *   <li>{@code gym.flow=INFO} — outcomes of every flow (default)</li>
 *   <li>{@code gym.flow.auth=DEBUG} — extra steps for sign-in and refresh</li>
 *   <li>{@code gym.flow.device=WARN} — hide routine device sync, keep failures</li>
 *   <li>{@code gym.flow.attendance=DEBUG} — include granted door events as well as denials</li>
 *   <li>{@code gym.flow.sync=DEBUG} — every outbox command: enqueue, supersede, dispatch, result</li>
 *   <li>{@code gym.flow.gateway=DEBUG} — every inbound gateway message (except heartbeats)</li>
 * </ul>
 */
public final class FlowLog {

    private FlowLog() {
    }

    /** Guards debug lines whose arguments cost something to build (e.g. parsing a payload). */
    public static boolean isDebugEnabled(String flow) {
        return logger(flow).isDebugEnabled();
    }

    public static void debug(String flow, String message, Object... args) {
        logger(flow).debug(message, args);
    }

    public static void info(String flow, String message, Object... args) {
        logger(flow).info(message, args);
    }

    public static void warn(String flow, String message, Object... args) {
        logger(flow).warn(message, args);
    }

    public static void error(String flow, String message, Object... args) {
        logger(flow).error(message, args);
    }

    private static Logger logger(String flow) {
        return LoggerFactory.getLogger("gym.flow." + flow);
    }
}
