package net.namesake.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.namesake.platform.Platform;

/** Fabric implementation of the loader seam. Registered via {@code META-INF/services}. */
public final class FabricPlatform implements Platform {

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public java.nio.file.Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
