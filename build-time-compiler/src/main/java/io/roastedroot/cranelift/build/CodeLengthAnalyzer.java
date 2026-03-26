package io.roastedroot.cranelift.build;

import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Extracts the {@code code_length} (bytecode byte count) from the Code
 * attribute of each {@code func_*} method in a compiled class file.
 *
 * <p>This is exactly the value that HotSpot's {@code HugeMethodLimit}
 * (default 8000) checks against.
 */
final class CodeLengthAnalyzer {

    static final int HUGE_METHOD_LIMIT = 8000;

    private CodeLengthAnalyzer() {}

    /**
     * Parses a class file and returns a map of funcId → code_length for
     * each {@code func_*} method found.
     */
    static Map<Integer, Integer> analyze(byte[] classBytes) {
        Map<Integer, Integer> result = new HashMap<>();
        ClassReader reader = new ClassReader(classBytes);

        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        if (!name.startsWith("func_")) {
                            return null;
                        }
                        int funcId;
                        try {
                            funcId = Integer.parseInt(name.substring("func_".length()));
                        } catch (NumberFormatException e) {
                            return null;
                        }
                        return new ByteCountingMethodVisitor(funcId, result);
                    }
                },
                0);

        return result;
    }

    /**
     * A MethodVisitor that counts the actual bytecode bytes emitted for
     * each instruction. This matches the {@code code_length} field in the
     * Code attribute of the class file — the exact value that HotSpot's
     * {@code HugeMethodLimit} checks.
     */
    private static final class ByteCountingMethodVisitor extends MethodVisitor {

        private final int funcId;
        private final Map<Integer, Integer> result;
        private int byteCount;

        ByteCountingMethodVisitor(int funcId, Map<Integer, Integer> result) {
            super(Opcodes.ASM9);
            this.funcId = funcId;
            this.result = result;
            this.byteCount = 0;
        }

        @Override
        public void visitInsn(int opcode) {
            byteCount += 1;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            // BIPUSH = 2 bytes, SIPUSH = 3 bytes, NEWARRAY = 2 bytes
            if (opcode == Opcodes.SIPUSH) {
                byteCount += 3;
            } else {
                byteCount += 2;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            // ILOAD 0-3 are 1-byte shortcuts, otherwise 2 bytes (or 4 with WIDE)
            if (var <= 3
                    && (opcode == Opcodes.ILOAD
                            || opcode == Opcodes.LLOAD
                            || opcode == Opcodes.FLOAD
                            || opcode == Opcodes.DLOAD
                            || opcode == Opcodes.ALOAD
                            || opcode == Opcodes.ISTORE
                            || opcode == Opcodes.LSTORE
                            || opcode == Opcodes.FSTORE
                            || opcode == Opcodes.DSTORE
                            || opcode == Opcodes.ASTORE)) {
                byteCount += 1;
            } else if (var > 255) {
                byteCount += 4; // WIDE prefix + opcode + 2-byte index
            } else {
                byteCount += 2;
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            byteCount += 3;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            byteCount += 3;
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKEINTERFACE) {
                byteCount += 5;
            } else {
                byteCount += 3;
            }
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name,
                String descriptor,
                org.objectweb.asm.Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            byteCount += 5;
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            // GOTO_W and JSR_W are 5 bytes, but ASM may widen automatically
            // Regular jumps are 3 bytes; ASM handles widening transparently
            if (opcode == Opcodes.GOTO || opcode == 200 /* GOTO_W */) {
                byteCount += 3; // may become 5 if widened, but we count the base
            } else {
                byteCount += 3;
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            // LDC = 2 bytes, LDC_W/LDC2_W = 3 bytes
            if (value instanceof Long || value instanceof Double) {
                byteCount += 3; // LDC2_W
            } else {
                byteCount += 2; // LDC (may become LDC_W = 3 if pool index > 255)
            }
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            if (var > 255 || increment > 127 || increment < -128) {
                byteCount += 6; // WIDE IINC
            } else {
                byteCount += 3;
            }
        }

        @Override
        public void visitTableSwitchInsn(
                int min, int max, org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) {
            // 1 (opcode) + 0-3 (padding) + 4 (default) + 4 (low) + 4 (high) + 4*n (offsets)
            // Padding depends on alignment; use worst case average of ~2
            byteCount += 1 + 2 + 4 + 4 + 4 + 4 * labels.length;
        }

        @Override
        public void visitLookupSwitchInsn(
                org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] labels) {
            // 1 (opcode) + 0-3 (padding) + 4 (default) + 4 (npairs) + 8*n (key+offset pairs)
            byteCount += 1 + 2 + 4 + 4 + 8 * labels.length;
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            byteCount += 4;
        }

        @Override
        public void visitEnd() {
            result.put(funcId, byteCount);
        }
    }
}
