package net.namesake.testing;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>Which of a class's fields carry a {@code ConstantValue} attribute — that is, which ones javac
 * folded into every use site.</b>
 *
 * <p>Added at session 15, and the reason is a hole in the guard it generalises.
 * {@code DayPlanTest.theSpreadFloorIsNotConfigurable} reads {@code DayPlan}'s own {@code <clinit>}
 * for four JDK property doors, which was the right instrument when the only way to make a wall
 * configurable was {@code System.getProperty}. Session 15 built a config package, and
 * {@code Config.get().spread()} called from that same initialiser records as
 * {@code net/namesake/config/Config#spread} — <b>none of the four doors, and green.</b>
 *
 * <p>This asks the question at the level the claim actually holds. A {@code static final int}
 * initialised from a constant expression gets a {@code ConstantValue} attribute, and JLS §13.1
 * requires every reference to it to be compiled to that literal. <b>So there is no field read left
 * in the bytecode to redirect</b> — not to a property, not to a config file, not to a database, and
 * not to a door nobody has thought of yet. Making the field configurable by any means at all
 * requires removing {@code final} or writing a non-constant initialiser, and either one removes the
 * attribute this reads.
 *
 * <p>It is a strictly stronger claim than "the initialiser does not call these four methods", and it
 * is one line per wall rather than one test per wall.
 */
public final class ConstantFields {

    private final Map<String, Object> constants = new LinkedHashMap<>();

    private ConstantFields() {
    }

    /** Every {@code static final} field of {@code owner} that javac folded, by name. */
    public static ConstantFields of(Class<?> owner) {
        ConstantFields fields = new ConstantFields();
        String resource = "/" + Type.getInternalName(owner) + ".class";
        try (InputStream in = owner.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("No class file for " + owner.getName()
                        + " on the test classpath at " + resource);
            }
            new ClassReader(in).accept(fields.new Reader(), ClassReader.SKIP_CODE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return fields;
    }

    /**
     * True when {@code name} is folded at compile time, and therefore cannot be read at runtime by
     * anything at all.
     */
    public boolean isFolded(String name) {
        return constants.containsKey(name);
    }

    /** The folded value, for an assertion that also wants to check what the wall is set to. */
    public Object value(String name) {
        return constants.get(name);
    }

    /** Everything folded, for a failure message that says what the class does have. */
    public Map<String, Object> all() {
        return Map.copyOf(constants);
    }

    private final class Reader extends ClassVisitor {

        Reader() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                                       Object value) {
            // `value` is non-null exactly when the field carries a ConstantValue attribute, which
            // javac emits only for a static final primitive or String with a constant initialiser.
            if (value != null) {
                constants.put(name, value);
            }
            return null;
        }
    }
}
