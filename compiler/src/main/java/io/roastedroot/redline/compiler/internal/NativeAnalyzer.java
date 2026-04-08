package io.roastedroot.redline.compiler.internal;

import com.dylibso.chicory.wasm.types.AnnotatedInstruction;
import com.dylibso.chicory.wasm.types.FunctionBody;
import java.util.List;

/**
 * Pre-pass over a function's instruction stream that determines reachability.
 *
 * <p>After an unconditional control transfer (BR, RETURN, UNREACHABLE, BR_TABLE),
 * all subsequent instructions are dead until an END or ELSE closes the enclosing
 * block. Nested BLOCK/LOOP/IF inside dead code are tracked via a nesting counter
 * so that only the END matching the block where the transfer occurred triggers
 * the end of the skip region.
 *
 * <p>Output: two parallel boolean arrays indexed by instruction index.
 * <ul>
 *   <li>{@code skip[i]} — instruction should not be emitted (dead code)
 *   <li>{@code scopeRestore[i]} — at this END, the block body was unreachable
 * </ul>
 */
final class NativeAnalyzer {

    private final boolean[] skip;
    private final boolean[] scopeRestore;

    private NativeAnalyzer(boolean[] skip, boolean[] scopeRestore) {
        this.skip = skip;
        this.scopeRestore = scopeRestore;
    }

    boolean skip(int index) {
        return skip[index];
    }

    boolean scopeRestore(int index) {
        return scopeRestore[index];
    }

    static NativeAnalyzer analyze(FunctionBody body) {
        List<AnnotatedInstruction> instructions = body.instructions();
        int count = instructions.size();
        boolean[] skip = new boolean[count];
        boolean[] scopeRestore = new boolean[count];

        boolean inDeadCode = false;
        int skipNesting = 0;

        for (int i = 0; i < count; i++) {
            AnnotatedInstruction ins = instructions.get(i);

            if (inDeadCode) {
                switch (ins.opcode()) {
                    case BLOCK:
                    case LOOP:
                    case IF:
                        skipNesting++;
                        skip[i] = true;
                        continue;

                    case END:
                        if (skipNesting > 0) {
                            skipNesting--;
                            skip[i] = true;
                            continue;
                        }
                        // This END closes the block where the transfer occurred
                        inDeadCode = false;
                        scopeRestore[i] = true;
                        continue;

                    case ELSE:
                        if (skipNesting > 0) {
                            skip[i] = true;
                            continue;
                        }
                        // ELSE at the exit level — else branch is reachable
                        inDeadCode = false;
                        continue;

                    default:
                        skip[i] = true;
                        continue;
                }
            }

            switch (ins.opcode()) {
                case UNREACHABLE:
                case BR:
                case RETURN:
                case BR_TABLE:
                    inDeadCode = true;
                    skipNesting = 0;
                    break;
                default:
                    break;
            }
        }

        return new NativeAnalyzer(skip, scopeRestore);
    }
}
