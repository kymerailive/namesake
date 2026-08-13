package net.namesake.neoforge;

import net.namesake.Namesake;
import net.neoforged.fml.common.Mod;

/** NeoForge bootstrap. Does nothing but hand control to the common entrypoint. */
@Mod(Namesake.MOD_ID)
public final class NamesakeNeoForge {

    public NamesakeNeoForge() {
        Namesake.init();
    }
}
