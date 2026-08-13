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
    public String minecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
