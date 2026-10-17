package com.example.gym.device;

/** Staff choices that become a desired revision. Nothing here links two device users by face hash. */
public final class ReviewDecisions {

    public static final String ACCEPT_SERVER = "ACCEPT_SERVER";
    /** Staff keep the reader's shown fields as desired on this reader. */
    public static final String ACCEPT_READER = "ACCEPT_READER";
    public static final String RESTORE = "RESTORE";
    public static final String REMOVE = "REMOVE";
    public static final String LINK = "LINK";
    public static final String CREATE = "CREATE";
    public static final String REJECT = "REJECT";

    public static final String REVIEW = "REVIEW";
    public static final String ENROLLMENT = "ENROLLMENT";
    public static final String SUSPENDED = "SUSPENDED";

    private ReviewDecisions() {
    }
}
