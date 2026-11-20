package dev.aivillages.core.kernel.runtime.activity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Lossless, bounded storage for canonical plan and recipe data inside one Job record. */
final class ActivityEncoding {
    private static final int CHUNK = 768;
    private static final int DEFLATE_OVERHEAD = 128;

    private ActivityEncoding() { }

    static List<String> deflate(byte[] payload, int maximum) {
        if (payload.length == 0 || payload.length > maximum) throw new IllegalArgumentException("Activity body byte limit");
        byte[] output = new byte[payload.length + DEFLATE_OVERHEAD];
        try (var deflater = new Deflater(Deflater.BEST_SPEED)) {
            deflater.setInput(payload);
            deflater.finish();
            int size = deflater.deflate(output);
            if (!deflater.finished()) throw new IllegalArgumentException("Activity compression limit");
            return chunks(Base64.getEncoder().encodeToString(Arrays.copyOf(output, size)));
        }
    }

    static byte[] inflate(Object encoded, int maximum) {
        int maxChunks = (((maximum + DEFLATE_OVERHEAD + 2) / 3) * 4 + CHUNK - 1) / CHUNK;
        if (!(encoded instanceof List<?> parts) || parts.isEmpty() || parts.size() > maxChunks)
            throw new IllegalArgumentException("Activity chunk list limit");
        var base64 = new StringBuilder();
        for (var part : parts) {
            if (!(part instanceof String text) || text.isEmpty() || text.length() > CHUNK)
                throw new IllegalArgumentException("Activity chunk limit");
            base64.append(text);
        }
        byte[] packed = Base64.getDecoder().decode(base64.toString());
        if (packed.length > maximum + DEFLATE_OVERHEAD || !chunks(Base64.getEncoder().encodeToString(packed)).equals(parts))
            throw new IllegalArgumentException("Noncanonical activity chunks");
        byte[] output = new byte[maximum + 1];
        try (var inflater = new Inflater()) {
            inflater.setInput(packed);
            int size = inflater.inflate(output);
            if (!inflater.finished() || inflater.getRemaining() != 0 || size == 0 || size > maximum)
                throw new IllegalArgumentException("Activity decompression limit or trailing data");
            return Arrays.copyOf(output, size);
        } catch (DataFormatException invalid) {
            throw new IllegalArgumentException("Invalid compressed activity body", invalid);
        }
    }

    static String utf8(byte[] bytes) {
        var result = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, result.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Invalid activity body UTF-8");
        return result;
    }

    private static List<String> chunks(String base64) {
        var parts = new ArrayList<String>();
        for (int start = 0; start < base64.length(); start += CHUNK)
            parts.add(base64.substring(start, Math.min(start + CHUNK, base64.length())));
        return List.copyOf(parts);
    }
}
