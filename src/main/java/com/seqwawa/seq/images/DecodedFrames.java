package com.seqwawa.seq.images;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

final class DecodedFrames {
    private static final int MAGIC = 0x53494D31;

    private DecodedFrames() {}

    static void validate(int w, int h, int count) throws IOException {
        if (w < 1
                || h < 1
                || w > 256
                || h > 256
                || count < 1
                || count > 400
                || (long) w * h * count > 12_000_000)
            throw new IOException("Invalid decoded preview dimensions");
    }

    static void write(Path path, ImageDecoder.Animation animation) throws IOException {
        int w = animation.width(), h = animation.height(), count = animation.frames().size();
        validate(w, h, count);
        try (var out =
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(MAGIC);
            out.writeInt(w);
            out.writeInt(h);
            out.writeInt(count);
            for (var frame : animation.frames()) {
                if (frame.argb().length != w * h
                        || frame.durationMs() < 1
                        || frame.durationMs() > 655350)
                    throw new IOException("Invalid decoded frame");
                out.writeInt(frame.durationMs());
                for (int pixel : frame.argb()) out.writeInt(pixel);
            }
        }
    }

    static ImageDecoder.Animation read(Path path) throws IOException {
        if (Files.size(path) < 16 || Files.size(path) > 48_001_616L)
            throw new IOException("Invalid decoded preview length");
        try (var in = Files.newInputStream(path)) {
            return read(in);
        }
    }

    static ImageDecoder.Animation read(InputStream stream) throws IOException {
        var in = new DataInputStream(new BufferedInputStream(stream));
        if (in.readInt() != MAGIC) throw new IOException("Invalid decoded preview header");
        int w = in.readInt(), h = in.readInt(), count = in.readInt();
        validate(w, h, count);
        var frames = new ArrayList<ImageDecoder.Frame>(count);
        byte[] packed = new byte[w * h * 4];
        var packedPixels = ByteBuffer.wrap(packed).asIntBuffer();
        for (int i = 0; i < count; i++) {
            int ms = in.readInt();
            if (ms < 1 || ms > 655350) throw new IOException("Invalid decoded timing");
            if (in.readNBytes(packed, 0, packed.length) != packed.length)
                throw new EOFException("Truncated decoded frame");
            int[] pixels = new int[w * h];
            packedPixels.rewind();
            packedPixels.get(pixels);
            frames.add(new ImageDecoder.Frame(pixels, ms));
        }
        if (in.read() != -1) throw new IOException("Unexpected decoded preview data");
        return new ImageDecoder.Animation(w, h, List.copyOf(frames));
    }
}
