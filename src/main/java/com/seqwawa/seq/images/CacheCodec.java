package com.seqwawa.seq.images;

import java.io.*;
import java.util.*;
import java.util.zip.*;

final class CacheCodec {
    enum Format {
        DEFLATE
    }

    record Encoded(Format format, int width, int height, List<Integer> durations, byte[] data) {
        long bytes() {
            return (long) data.length + 4L * durations.size();
        }
    }

    private CacheCodec() {}

    static Encoded encode(ImageDecoder.Animation animation) throws IOException {
        return encode(animation, new MediaCancellation());
    }

    static Encoded encode(ImageDecoder.Animation animation, MediaCancellation cancellation)
            throws IOException {
        cancellation.check();
        int w = animation.width(), h = animation.height(), count = animation.frames().size();
        DecodedFrames.validate(w, h, count);
        var durations = new ArrayList<Integer>(count);
        var bytes = new ByteArrayOutputStream();
        var deflater = new Deflater(Deflater.BEST_SPEED);
        int pixelsPerFrame = w * h;
        byte[] row = new byte[pixelsPerFrame * 4];
        try (var output = new DeflaterOutputStream(bytes, deflater, 65536)) {
            for (var frame : animation.frames()) {
                cancellation.check();
                if (frame.argb().length != w * h
                        || frame.durationMs() < 1
                        || frame.durationMs() > 655350)
                    throw new IOException("Invalid preview frame");
                durations.add(frame.durationMs());
                // Grouping each colour channel improves DEFLATE compression.
                for (int i = 0; i < pixelsPerFrame; i++) {
                    int pixel = frame.argb()[i];
                    row[i] = (byte) (pixel >>> 24);
                    row[pixelsPerFrame + i] = (byte) (pixel >>> 16);
                    row[2 * pixelsPerFrame + i] = (byte) (pixel >>> 8);
                    row[3 * pixelsPerFrame + i] = (byte) pixel;
                }
                output.write(row);
            }
        } finally {
            deflater.end();
        }
        return new Encoded(Format.DEFLATE, w, h, List.copyOf(durations), bytes.toByteArray());
    }

    static ImageDecoder.Animation decode(Encoded encoded) throws IOException {
        return decode(encoded, new MediaCancellation());
    }

    static ImageDecoder.Animation decode(Encoded encoded, MediaCancellation cancellation)
            throws IOException {
        cancellation.check();
        int w = encoded.width(), h = encoded.height(), count = encoded.durations().size();
        DecodedFrames.validate(w, h, count);
        if (encoded.format() != Format.DEFLATE || encoded.data().length > 64 * 1024 * 1024)
            throw new IOException("Invalid preview cache");
        var frames = new ArrayList<ImageDecoder.Frame>(count);
        var source = new ByteArrayInputStream(encoded.data());
        var inflater = new Inflater();
        int n = w * h;
        byte[] bytes = new byte[n * 4];
        try (var input = new InflaterInputStream(source, inflater, 65536)) {
            for (int duration : encoded.durations()) {
                cancellation.check();
                if (duration < 1 || duration > 655350)
                    throw new IOException("Invalid cached timing");
                if (input.readNBytes(bytes, 0, bytes.length) != bytes.length)
                    throw new IOException("Truncated preview cache");
                int[] pixels = new int[n];
                for (int i = 0; i < n; i++)
                    pixels[i] =
                            ((bytes[i] & 255) << 24)
                                    | ((bytes[n + i] & 255) << 16)
                                    | ((bytes[2 * n + i] & 255) << 8)
                                    | (bytes[3 * n + i] & 255);
                frames.add(new ImageDecoder.Frame(pixels, duration));
            }
            if (input.read() != -1
                    || !inflater.finished()
                    || inflater.getRemaining() != 0
                    || source.available() != 0)
                throw new IOException("Unexpected preview cache data");
        } finally {
            inflater.end();
        }
        return new ImageDecoder.Animation(w, h, List.copyOf(frames));
    }
}
