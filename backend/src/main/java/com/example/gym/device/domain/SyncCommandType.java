package com.example.gym.device.domain;

/**
 * Commands staff send to one reader through its gateway. Member state reaches readers as desired
 * revisions and attendance is read by the gateway's poll, so neither is a command.
 */
public enum SyncCommandType {
    OPEN_DOOR,
    CLOSE_DOOR,
    /** Staff ask the gateway to read one reader now; it sends that reader's whole list even if unchanged. */
    SCAN_READER
}
