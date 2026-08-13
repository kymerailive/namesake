package net.namesake.fabric;

import net.fabricmc.api.ModInitializer;
import net.namesake.Namesake;

/** Fabric bootstrap. Does nothing but hand control to the common entrypoint. */
public final class NamesakeFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Namesake.init();
    }
}
