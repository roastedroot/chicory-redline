package io.roastedroot.cranelift.compiler.internal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Value stack tracking Cranelift value IDs with scope-aware restore for
 * unreachable code handling. Modeled after {@code TypeStack} in the
 * {@code compiler/} module.
 *
 * <p>At each block entry, {@link #enterScope} saves a "restored" snapshot
 * of the stack (current minus block params, plus merge-block param IDs).
 * When an unreachable block reaches END, {@link #scopeRestore} replaces
 * the actual stack with this snapshot, ensuring correct-typed values are
 * available for subsequent code.
 */
final class NativeValueStack {

    private final Deque<Integer> stack = new ArrayDeque<>();
    private final Deque<int[]> restoreStack = new ArrayDeque<>();

    void push(int valueId) {
        stack.push(valueId);
    }

    int pop() {
        return stack.pop();
    }

    int peek() {
        return stack.peek();
    }

    int size() {
        return stack.size();
    }

    boolean isEmpty() {
        return stack.isEmpty();
    }

    void trimTo(int height) {
        while (stack.size() > height) {
            stack.pop();
        }
    }

    /**
     * Enter a new scope. Precomputes the restored stack snapshot:
     * current stack (bottom-to-top) minus {@code paramCount} entries from top,
     * plus {@code mergeParamIds} appended on top.
     *
     * @param paramCount   number of block input params to remove
     * @param mergeParamIds Cranelift value IDs for the merge block's params
     *                      (one per block return type)
     */
    void enterScope(int paramCount, int[] mergeParamIds) {
        // Snapshot current stack as array (bottom-to-top)
        // Deque iterator goes top-to-bottom, so we reverse
        int[] snapshot = new int[stack.size()];
        int idx = stack.size() - 1;
        for (int val : stack) {
            snapshot[idx--] = val;
        }

        // Build restored: snapshot minus top paramCount entries, plus mergeParamIds
        int baseSize = Math.max(0, snapshot.length - paramCount);
        int[] restored = new int[baseSize + mergeParamIds.length];
        System.arraycopy(snapshot, 0, restored, 0, baseSize);
        System.arraycopy(mergeParamIds, 0, restored, baseSize, mergeParamIds.length);

        restoreStack.push(restored);
    }

    /**
     * Restore the value stack to the precomputed snapshot for the current scope.
     * Used at END when the block body was unreachable.
     */
    void scopeRestore() {
        int[] restored = restoreStack.peek();
        stack.clear();
        // Push bottom-to-top (index 0 is bottom)
        for (int val : restored) {
            // addLast puts at bottom of Deque (which is the "bottom" of our stack)
            stack.addLast(val);
        }
    }

    /**
     * Exit the current scope, discarding the saved restore snapshot.
     */
    void exitScope() {
        restoreStack.pop();
    }
}
