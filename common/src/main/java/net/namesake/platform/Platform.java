package net.namesake.platform;

import java.util.NoSuchElementException;
import java.util.ServiceLoader;

/**
 * The loader-specific seam.
 *
 * <p>Implemented once per loader module and registered through {@code META-INF/services}. Common
 * code never references Fabric or NeoForge types directly — if you find yourself wanting to, the
 * capability belongs on this interface instead.
 *
 * <p>Keep this interface small. Every method added here is a method that must be implemented and
 * kept correct twice.
 */
public interface Platform {

    /** Human-readable loader name, e.g. {@code "Fabric"} or {@code "NeoForge"}. */
    String loaderName();

    /** True in a development environment — used to gate expensive assertions and debug commands. */
    boolean isDevelopmentEnvironment();

    // Deliberately NOT here: the Minecraft version. It is available from vanilla via
    // SharedConstants.getCurrentVersion(), so putting it on this interface would mean writing and
    // maintaining the same answer twice for no benefit. Before adding a method here, check whether
    // vanilla already answers it in common.

    /**
     * Resolves the loader's implementation.
     *
     * @throws IllegalStateException if no implementation is on the classpath, which means the
     *                               loader module failed to register its service file
     */
    static Platform get() {
        return Holder.INSTANCE;
    }

    /** Lazy holder so the {@link ServiceLoader} lookup happens once, on first use. */
    final class Holder {
        static final Platform INSTANCE = load();

        private Holder() {
        }

        private static Platform load() {
            try {
                return ServiceLoader.load(Platform.class).findFirst().orElseThrow();
            } catch (NoSuchElementException e) {
                throw new IllegalStateException(
                        "No Platform implementation found. The loader module is missing its "
                                + "META-INF/services/net.namesake.platform.Platform entry.", e);
            }
        }
    }
}
