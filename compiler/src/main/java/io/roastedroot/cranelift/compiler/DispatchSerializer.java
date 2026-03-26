package io.roastedroot.cranelift.compiler;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serializes and deserializes the dispatch map ({@code boolean[]}) that
 * determines which functions are dispatched to the native machine vs the
 * AOT bytecode machine.
 *
 * <p>Format: 4-byte int (function count) + 1 byte per function (0 = AOT, 1 = native).
 */
public final class DispatchSerializer {

    private DispatchSerializer() {}

    public static void serialize(boolean[] isNative, OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(isNative.length);
        for (boolean b : isNative) {
            dos.writeByte(b ? 1 : 0);
        }
        dos.flush();
    }

    public static boolean[] deserialize(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int count = dis.readInt();
        boolean[] isNative = new boolean[count];
        for (int i = 0; i < count; i++) {
            isNative[i] = dis.readByte() != 0;
        }
        return isNative;
    }
}
