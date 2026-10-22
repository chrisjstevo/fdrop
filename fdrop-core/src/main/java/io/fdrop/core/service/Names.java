package io.fdrop.core.service;

import io.fdrop.core.error.FdropException;

/** Validation for names users type: folders, keys, destinations. */
public final class Names {

    private Names() {
    }

    /** Folder names: 1–100 characters, no {@code /}, no control characters, not {@code .} or {@code ..}. */
    public static String folderName(String name) {
        if (name == null) {
            throw FdropException.badRequest("name is required");
        }
        String n = name.strip();
        if (n.isEmpty() || n.length() > 100) {
            throw FdropException.badRequest("folder names are 1 to 100 characters");
        }
        if (n.equals(".") || n.equals("..")) {
            throw FdropException.badRequest("'.' and '..' are not folder names");
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f) {
                throw FdropException.badRequest("folder names cannot contain '/' or control characters");
            }
        }
        return n;
    }

    public static String label(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw FdropException.badRequest(field + " is required");
        }
        String v = value.strip();
        if (v.length() > max) {
            throw FdropException.badRequest(field + " is longer than " + max + " characters");
        }
        return v;
    }
}
