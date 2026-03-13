package org.example.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathUtilsTest {

    @Test
    void uniqueName_shouldReturnOriginalWhenNotUsed() {
        Set<String> used = new LinkedHashSet<>();
        assertEquals("file.txt", PathUtils.uniqueName(used, "file.txt"));
    }

    @Test
    void uniqueName_shouldAppendSuffixWhenCollision() {
        Set<String> used = new LinkedHashSet<>();
        used.add("file.txt");
        used.add("file_(1).txt");

        assertEquals("file_(2).txt", PathUtils.uniqueName(used, "file.txt"));
    }
}
