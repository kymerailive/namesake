package net.namesake.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every class this mod compiles, for the tests that have to check a property of <i>all</i> of them.
 *
 * <p>"Every serverbound payload is behind a gate" and "every persisted social value has a consumer"
 * are both claims about a set nobody maintains by hand. A test that walks a hand-written list
 * proves nothing about the class somebody adds tomorrow, which is the only case that matters.
 *
 * <p>Reads the compiled output directory handed over by {@code common/build.gradle} rather than
 * guessing at the classpath, and loads classes without initialising them — running a static
 * initialiser during a scan would drag in half of Minecraft.
 */
public final class ModClasses {

    private static final String CLASSES_PROPERTY = "namesake.mainClasses";

    private ModClasses() {
    }

    public static List<Class<?>> all() {
        Path root = Path.of(required(CLASSES_PROPERTY));
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Compiled classes not found at " + root
                    + ". This test scans the build output; see the test task in common/build.gradle.");
        }

        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> className(root, path))
                    .sorted()
                    .forEach(name -> classes.add(load(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (classes.isEmpty()) {
            throw new IllegalStateException("Scanned " + root + " and found no classes. A scan that "
                    + "finds nothing passes every test in this file, so it fails here instead.");
        }
        return classes;
    }

    /** The repository root, for tests that read a document rather than a class. */
    public static Path repoRoot() {
        return Path.of(required("namesake.repoRoot"));
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("System property '" + property + "' is not set. It is "
                    + "set by the test task in common/build.gradle; running these tests outside "
                    + "Gradle needs it passed by hand.");
        }
        return value;
    }

    private static String className(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace('\\', '.').replace('/', '.');
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, ModClasses.class.getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            throw new IllegalStateException("Could not load " + name + " for scanning", e);
        }
    }
}
