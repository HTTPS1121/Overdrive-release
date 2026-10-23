package com.overdrive.app.util;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Completeness gate for the relocatable scratch root.
 *
 * <p>Shell text that merely CONTAINS {@code /data/local/tmp} is rewritten at
 * the spawn chokepoints ({@code ScratchRoot.prepare}/{@code remap}), so it may
 * stay literal. Anything that names a path directly — a constant, a
 * {@code File} argument, a map value, a default, a {@code when} branch — must
 * go through {@link ScratchRoot#path} or the Shark 6 build silently keeps
 * touching the legacy directory. Those sites are recognisable: the string
 * literal BEGINS with the root. Environment maps handed to a child process are
 * checked separately because they are not shell text either.
 */
public class ScratchRootCompletenessGateTest {

    /** A literal that starts with the legacy root and is not a look-alike sibling. */
    private static final Pattern LEADING_LITERAL =
            Pattern.compile("\"/data/local/tmp(?![A-Za-z0-9_.\\-])");
    private static final Pattern ENV_PUT =
            Pattern.compile("environment\\(\\)\\s*\\.\\s*put\\(.*?/data/local/tmp");
    private static final Pattern LD_LIBRARY_PATH =
            Pattern.compile("LD_LIBRARY_PATH.*?/data/local/tmp");

    private static final List<String> ALLOWLIST = List.of(
            // Defines LEGACY_DIR itself.
            "app/src/main/java/com/overdrive/app/util/ScratchRoot.java",
            // Resolves OVERDRIVE_SCRATCH at load and keeps the legacy root only
            // as the documented fallback (see reason_file_path()).
            "app/src/main/cpp/camera/fast_cam_release_guard.c"
    );

    @Test
    public void everyDirectPathReferenceGoesThroughScratchRoot() throws IOException {
        Path root = repositoryRoot();
        Path main = root.resolve("app/src/main");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            files.filter(Files::isRegularFile)
                    .filter(ScratchRootCompletenessGateTest::isSource)
                    .sorted()
                    .forEach(file -> scan(root, file, violations));
        }
        assertTrue("Direct /data/local/tmp references that bypass ScratchRoot ("
                        + violations.size() + "):\n" + String.join("\n", violations),
                violations.isEmpty());
    }

    private static boolean isSource(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".kt")
                || name.endsWith(".c") || name.endsWith(".cc")
                || name.endsWith(".cpp") || name.endsWith(".h");
    }

    private static void scan(Path root, Path file, List<String> violations) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (ALLOWLIST.contains(relative)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(relative + ": unreadable (" + e.getMessage() + ")");
            return;
        }
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (inBlockComment) {
                if (trimmed.contains("*/")) inBlockComment = false;
                continue;
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) inBlockComment = true;
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
            if (!line.contains("/data/local/tmp")) continue;
            if (LEADING_LITERAL.matcher(line).find()
                    || ENV_PUT.matcher(line).find()
                    || LD_LIBRARY_PATH.matcher(line).find()) {
                violations.add(relative + ":" + (i + 1) + ": " + trimmed);
            }
        }
    }

    static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("app/src/main"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found from " + System.getProperty("user.dir"));
    }
}
