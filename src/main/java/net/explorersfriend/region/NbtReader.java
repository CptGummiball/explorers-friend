package net.explorersfriend.region;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, defensive NBT (Named Binary Tag) reader — an original implementation from
 * the publicly documented format. Produces plain Java values:
 * Byte/Short/Integer/Long/Float/Double, byte[]/int[]/long[], String,
 * List&lt;Object&gt;, Map&lt;String,Object&gt;.
 *
 * <p>Hard limits (depth, string length, collection sizes) protect against corrupt or
 * hostile data. Stateless and thread-safe.</p>
 */
public final class NbtReader {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_STRING_LENGTH = 1 << 16;
    private static final int MAX_COLLECTION_SIZE = 1 << 26; // 64M entries: far above any real chunk

    private NbtReader() {
    }

    /** Reads a root compound (tag type 10 with a name, per spec). */
    public static Map<String, Object> readRootCompound(DataInput in) throws IOException {
        byte type = in.readByte();
        if (type != 10) {
            throw new IOException("root tag is type " + type + ", expected compound (10)");
        }
        readString(in); // root name, normally empty
        Object root = readPayload(in, (byte) 10, 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> compound = (Map<String, Object>) root;
        return compound;
    }

    private static Object readPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting deeper than " + MAX_DEPTH);
        }
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int length = checkedLength(in.readInt(), "byte array");
                byte[] data = new byte[length];
                in.readFully(data);
                yield data;
            }
            case 8 -> readString(in);
            case 9 -> {
                byte elementType = in.readByte();
                int length = checkedLength(in.readInt(), "list");
                if (length > 0 && elementType == 0) {
                    throw new IOException("non-empty list of end tags");
                }
                List<Object> list = new ArrayList<>(Math.min(length, 4096));
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(in, elementType, depth + 1));
                }
                yield list;
            }
            case 10 -> {
                Map<String, Object> compound = new HashMap<>();
                while (true) {
                    byte childType = in.readByte();
                    if (childType == 0) {
                        break;
                    }
                    String name = readString(in);
                    compound.put(name, readPayload(in, childType, depth + 1));
                }
                yield compound;
            }
            case 11 -> {
                int length = checkedLength(in.readInt(), "int array");
                int[] data = new int[length];
                for (int i = 0; i < length; i++) {
                    data[i] = in.readInt();
                }
                yield data;
            }
            case 12 -> {
                int length = checkedLength(in.readInt(), "long array");
                long[] data = new long[length];
                for (int i = 0; i < length; i++) {
                    data[i] = in.readLong();
                }
                yield data;
            }
            default -> throw new IOException("unknown NBT tag type " + type);
        };
    }

    private static String readString(DataInput in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_STRING_LENGTH) {
            throw new IOException("NBT string of " + length + " bytes");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        // Mojang uses (modified) UTF-8; plain UTF-8 decoding is fine for identifiers.
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int checkedLength(int length, String what) throws IOException {
        if (length < 0 || length > MAX_COLLECTION_SIZE) {
            throw new IOException("invalid " + what + " length " + length);
        }
        return length;
    }

    // --- typed accessors over the generic tree ----------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    public static String string(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof String s ? s : null;
    }

    public static long[] longArray(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof long[] arr ? arr : null;
    }

    public static int intValue(Map<String, Object> parent, String key, int fallback) {
        Object value = parent.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }
}
