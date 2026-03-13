package org.example.util;

import java.util.Set;

public final class PathUtils {
    private PathUtils() {
    }

    public static String uniqueName(Set<String> used, String name) {
        if (!used.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        String candidate;
        do {
            candidate = base + "_(" + i++ + ")" + ext;
        } while (used.contains(candidate));
        return candidate;
    }
}
