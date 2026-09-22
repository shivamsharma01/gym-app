package com.example.gym.member;

/**
 * How a member row was created. UI and REST API share {@link #MANUAL}; device roster import uses
 * {@link #DEVICE_IMPORT}.
 */
public enum MemberCreationSource {
    MANUAL,
    DEVICE_IMPORT
}
