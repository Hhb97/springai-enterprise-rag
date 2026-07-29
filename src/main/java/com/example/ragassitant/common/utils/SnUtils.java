package com.example.ragassitant.common.utils;

public final class SnUtils {

    private SnUtils() {}

    public static String normalize(String sn) {
        if (sn == null) return "";
        return sn.trim().replaceAll("[^a-zA-Z0-9_-]", "");
    }
}