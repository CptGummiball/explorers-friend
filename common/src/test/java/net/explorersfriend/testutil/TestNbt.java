package net.explorersfriend.testutil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Minimal NBT writer for building synthetic chunk data in tests. Supports the tag
 * types the mod's reader consumes: Byte, Int, Long, String, List, Compound, long[].
 */
public final class TestNbt {

    private TestNbt() {
    }

    public static byte[] rootCompound(Map<String, Object> values) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeByte(10);
            out.writeUTF("");
            writeCompoundPayload(out, values);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeCompoundPayload(DataOutputStream out, Map<String, Object> values) throws IOException {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            writeNamed(out, entry.getKey(), entry.getValue());
        }
        out.writeByte(0);
    }

    private static void writeNamed(DataOutputStream out, String name, Object value) throws IOException {
        out.writeByte(typeOf(value));
        out.writeUTF(name);
        writePayload(out, value);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object value) throws IOException {
        switch (value) {
            case Byte b -> out.writeByte(b);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case String s -> out.writeUTF(s);
            case long[] longs -> {
                out.writeInt(longs.length);
                for (long l : longs) {
                    out.writeLong(l);
                }
            }
            case List<?> list -> {
                byte elementType = list.isEmpty() ? 0 : typeOf(list.get(0));
                out.writeByte(elementType);
                out.writeInt(list.size());
                for (Object element : list) {
                    writePayload(out, element);
                }
            }
            case Map<?, ?> map -> writeCompoundPayload(out, (Map<String, Object>) map);
            default -> throw new IllegalArgumentException("unsupported test NBT type: " + value.getClass());
        }
    }

    private static byte typeOf(Object value) {
        return switch (value) {
            case Byte ignored -> 1;
            case Integer ignored -> 3;
            case Long ignored -> 4;
            case String ignored -> 8;
            case List<?> ignored -> 9;
            case Map<?, ?> ignored -> 10;
            case long[] ignored -> 12;
            default -> throw new IllegalArgumentException("unsupported test NBT type: " + value.getClass());
        };
    }
}
