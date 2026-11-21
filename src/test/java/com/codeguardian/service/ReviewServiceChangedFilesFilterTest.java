package com.codeguardian.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReviewServiceChangedFilesFilterTest {

    private final Path root = Paths.get("build/tmp/review-root").toAbsolutePath().normalize();

    @Test
    void should_keep_only_changed_files() {
        List<Path> scanned = List.of(
                root.resolve("src/main/java/A.java"),
                root.resolve("src/main/java/B.java"),
                root.resolve("README.md"));

        List<Path> filtered = ReviewService.filterByChangedFiles(scanned, root,
                List.of("src/main/java/A.java", "README.md"));

        assertEquals(List.of(root.resolve("src/main/java/A.java"), root.resolve("README.md")), filtered);
    }

    @Test
    void should_normalize_backslashes_and_dot_prefix() {
        List<Path> scanned = List.of(root.resolve("src/A.java"), root.resolve("src/B.java"));

        List<Path> filtered = ReviewService.filterByChangedFiles(scanned, root,
                List.of("./src/A.java", "src\\B.java"));

        assertEquals(2, filtered.size());
    }

    @Test
    void should_return_all_files_when_changed_list_is_empty() {
        List<Path> scanned = List.of(root.resolve("src/A.java"));

        assertSame(scanned, ReviewService.filterByChangedFiles(scanned, root, List.of()));
        assertSame(scanned, ReviewService.filterByChangedFiles(scanned, root, null));
    }

    @Test
    void should_return_empty_when_no_changed_file_matches() {
        List<Path> scanned = List.of(root.resolve("src/A.java"));

        List<Path> filtered = ReviewService.filterByChangedFiles(scanned, root,
                List.of("docs/README.md"));

        assertEquals(List.of(), filtered);
    }

    @Test
    void should_normalize_relative_path() {
        assertEquals("src/main/A.java", ReviewService.normalizeRelativePath("./src/main/A.java"));
        assertEquals("src/main/A.java", ReviewService.normalizeRelativePath("/src/main/A.java"));
        assertEquals("src/main/A.java", ReviewService.normalizeRelativePath("src\\main\\A.java"));
        assertEquals("", ReviewService.normalizeRelativePath(null));
    }
}
