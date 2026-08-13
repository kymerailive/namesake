package net.namesake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 00's proof that the test source set actually runs.
 *
 * <p>Deliberately not tautological: this asserts a real invariant that would break the mod at
 * runtime if violated, and it fails if someone edits {@link Namesake#MOD_ID} to something Minecraft
 * cannot use as a {@code ResourceLocation} namespace.
 */
class NamesakeTest {

    /** Minecraft's namespace rule: {@code [a-z0-9_.-]+}. See {@code ResourceLocation.isValidNamespace}. */
    private static final Pattern VALID_NAMESPACE = Pattern.compile("[a-z0-9_.\\-]+");

    @Test
    @DisplayName("MOD_ID is a legal ResourceLocation namespace")
    void modIdIsAValidNamespace() {
        assertTrue(VALID_NAMESPACE.matcher(Namesake.MOD_ID).matches(),
                () -> "MOD_ID '" + Namesake.MOD_ID + "' is not a legal Minecraft namespace; "
                        + "it must match " + VALID_NAMESPACE.pattern());
    }

    @Test
    @DisplayName("MOD_ID matches the gradle mod_id property baked into both loader manifests")
    void modIdMatchesBuildConfiguration() {
        // gradle.properties declares mod_id=namesake and both fabric.mod.json and
        // neoforge.mods.toml are templated from it. A mismatch here means the Java constant and
        // the manifests have drifted, which fails only at runtime and only on one loader.
        assertTrue("namesake".equals(Namesake.MOD_ID),
                () -> "MOD_ID '" + Namesake.MOD_ID + "' has drifted from gradle.properties mod_id");
    }
}
