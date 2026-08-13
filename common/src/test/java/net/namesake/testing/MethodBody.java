package net.namesake.testing;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads what a compiled method actually does.
 *
 * <p>Both enforcement tests this session need a claim that reflection cannot make. Rule 5 says a
 * social value needs a consumer, and a method that merely <i>exists</i> with the right name is not
 * a consumer — it has to read the field. Hard rule 6 says a verb must authorize, and an
 * {@code authorize} that ignores both its parameters is not a gate, however correctly it is typed.
 *
 * <p><b>Lambdas.</b> {@code javac} compiles a lambda body into a synthetic method named
 * {@code lambda$<enclosing>$<n>}, so the enclosing method's own bytecode contains only an
 * {@code invokedynamic}. Both checks would be defeated by writing the interesting line inside a
 * {@code map} or {@code filter} — which is exactly how {@code PersonaService.reapStrayMint} reads
 * {@code birthTick}. So lambda bodies belonging to the method are folded in, and the {@code Handle}
 * arguments of an {@code invokedynamic} are read too, which is where a method reference such as
 * {@code Persona::birthTick} hides.
 */
public final class MethodBody {

    private final Set<String> invocations = new LinkedHashSet<>();
    private final Set<Integer> loadedSlots = new HashSet<>();
    private boolean found;

    private MethodBody() {
    }

    /**
     * Everything {@code owner#methodName} does, including its lambda bodies.
     *
     * <p>Bridge and synthetic methods with the same name are skipped: a covariant override leaves a
     * bridge behind whose whole body is "load every parameter and delegate", which would make the
     * parameter check pass for free.
     */
    public static MethodBody of(Class<?> owner, String methodName) {
        MethodBody body = new MethodBody();
        String lambdaPrefix = "lambda$" + methodName + "$";

        read(owner, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                boolean bridgeOrSynthetic =
                        (access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0;
                boolean isTheMethod = name.equals(methodName) && !bridgeOrSynthetic;
                boolean isItsLambda = name.startsWith(lambdaPrefix);
                if (!isTheMethod && !isItsLambda) {
                    return null;
                }
                body.found |= isTheMethod;
                return body.new Recorder();
            }
        });

        if (!body.found) {
            throw new IllegalStateException("No method named '" + methodName + "' was found in "
                    + owner.getName() + ". The name recorded against it has drifted from the code, "
                    + "which is exactly what this check is for.");
        }
        return body;
    }

    /** True if the method calls {@code owner.name(...)}, directly or through a method reference. */
    public boolean invokes(Class<?> owner, String name) {
        return invocations.contains(Type.getInternalName(owner) + "#" + name);
    }

    public boolean invokesAny(Class<?> owner, Iterable<String> names) {
        for (String name : names) {
            if (invokes(owner, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the method reads at least one of its declared parameters.
     *
     * @param descriptor the method descriptor, e.g. {@code (Lnet/minecraft/…/ServerPlayer;L…;)L…;}
     * @param isStatic   whether slot 0 is a parameter rather than {@code this}
     */
    public boolean readsAnyParameter(String descriptor, boolean isStatic) {
        int slot = isStatic ? 0 : 1;
        for (Type parameter : Type.getArgumentTypes(descriptor)) {
            if (loadedSlots.contains(slot)) {
                return true;
            }
            slot += parameter.getSize();
        }
        return false;
    }

    private static void read(Class<?> owner, ClassVisitor visitor) {
        String resource = "/" + Type.getInternalName(owner) + ".class";
        try (InputStream in = owner.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("No class file for " + owner.getName()
                        + " on the test classpath at " + resource);
            }
            new ClassReader(in).accept(visitor, ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final class Recorder extends MethodVisitor {

        Recorder() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            invocations.add(owner + "#" + name);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethod,
                                           Object... bootstrapArguments) {
            // A method reference such as Persona::birthTick appears here and nowhere else.
            for (Object argument : bootstrapArguments) {
                if (argument instanceof Handle handle) {
                    invocations.add(handle.getOwner() + "#" + handle.getName());
                }
            }
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            // Loads only. A store into a parameter slot is the method overwriting its input, which
            // is the opposite of reading it.
            if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                loadedSlots.add(varIndex);
            }
        }
    }
}
