package net.namesake.neoforge;

import net.namesake.platform.Platform;
import net.neoforged.fml.loading.FMLLoader;

/** NeoForge implementation of the loader seam. Registered via {@code META-INF/services}. */
public final class NeoForgePlatform implements Platform {

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }
}
