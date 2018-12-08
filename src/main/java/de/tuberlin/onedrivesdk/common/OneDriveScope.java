package de.tuberlin.onedrivesdk.common;

/**
 * The different scopes for using the OneDrive API. These scopes are used in the authentication process.
 */
public enum OneDriveScope {
    OFFLINE_ACCESS("offline_access"),
    FILES_READ("files.read"),
    FILES_READ_ALL("files.read.all"),
    FILES_READWRITE("files.readwrite"),
    FILES_READWRITE_ALL("files.readwrite.all");

    private String code;

    OneDriveScope(String s) {
        this.code = s;
    }

    public String getCode() {
        return code;
    }
}
