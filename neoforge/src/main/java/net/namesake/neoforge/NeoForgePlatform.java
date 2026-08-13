package net.namesake.neoforge;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.locating.IModFile;

import net.namesake.platform.Platform;

/** NeoForge implementation of the loader seam. Registered via {@code META-INF/services}. */
public final class NeoForgePlatform implements Platform {

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public String minecraftVersion() {
        IModFile mc = FMLLoader.getLoadingModList().getModFileById("minecraft").getFile();
        return mc.getModInfos().isEmpty() ? "unknown" : mc.getModInfos().getFirst().getVersion().toString();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }
}
