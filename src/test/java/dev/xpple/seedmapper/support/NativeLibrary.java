package dev.xpple.seedmapper.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Loads the native Cubiomes library for tests. In the game this is done by the
 * mod initialiser, which is not run for unit tests.
 */
public final class NativeLibrary {

    private static boolean loaded = false;

    private NativeLibrary() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String name = "cubiomes";
        if (!os.contains("mac")) {
            name += arch.equals("aarch64") || arch.equals("arm64") ? "_arm" : "_x86";
        }
        Path library = Path.of("src", "main", "resources", System.mapLibraryName(name)).toAbsolutePath();
        if (!Files.exists(library)) {
            throw new IllegalStateException("Could not find the Cubiomes library at " + library + ", compile it first");
        }
        System.load(library.toString());
        loaded = true;
    }
}
