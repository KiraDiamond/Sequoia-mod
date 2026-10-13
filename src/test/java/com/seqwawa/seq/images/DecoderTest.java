package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

/** Decodes through the bundled, sandboxed helper and reads the frames back through the cache codec. */
class DecoderTest {
    private static byte[] encode(BufferedImage image, String format) throws IOException {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes));
        return bytes.toByteArray();
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = DecoderTest.class.getResourceAsStream(name)) {
            return stream.readAllBytes();
        }
    }

    @Test
    void pngPixelsAndAlphaSurviveExactly() throws Exception {
        var picture = new BufferedImage(17, 19, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 19; y++)
            for (int x = 0; x < 17; x++)
                picture.setRGB(
                        x, y, ((x % 3 == 0 ? 0 : x % 3 == 1 ? 127 : 255) << 24) | (x * 13 << 16) | (y * 11 << 8) | 19);
        var actual = ImageDecoder.decode(encode(picture, "png"));
        assertEquals(17, actual.width());
        assertEquals(19, actual.height());
        assertEquals(1, actual.frames().size());
        assertArrayEquals(
                picture.getRGB(0, 0, 17, 19, null, 0, 17),
                actual.frames().getFirst().argb());
    }

    @Test
    void largeImagesKeepTheirAspectRatio() throws Exception {
        var actual = ImageDecoder.decode(encode(new BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB), "png"));
        assertEquals(256, actual.width());
        assertEquals(128, actual.height());
        assertEquals(0, actual.frames().getFirst().argb()[0], "transparency survives scaling");
    }

    @Test
    void jpegDecodes() throws Exception {
        var picture = new BufferedImage(17, 19, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 19; y++) for (int x = 0; x < 17; x++) picture.setRGB(x, y, 0xffe01020);
        int pixel =
                ImageDecoder.decode(encode(picture, "jpeg")).frames().getFirst().argb()[0];
        assertEquals(255, pixel >>> 24);
        assertTrue(((pixel >>> 16) & 255) > 200);
        assertTrue(((pixel >>> 8) & 255) < 40);
    }

    @Test
    void gifTimingAndDisposalArePreserved() throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            frame(writer, Color.RED, 0, "none", 10, 2);
            frame(writer, Color.BLUE, 0, "restoreToPrevious", 20, 1);
            frame(writer, Color.GREEN, 1, "none", 30, 1);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        var animation = ImageDecoder.decode(bytes.toByteArray());
        assertEquals(3, animation.frames().size());
        assertEquals(100, animation.frames().get(0).durationMs());
        assertEquals(200, animation.frames().get(1).durationMs());
        assertEquals(Color.RED.getRGB(), animation.frames().get(2).argb()[0]);
        assertEquals(Color.GREEN.getRGB(), animation.frames().get(2).argb()[1]);
        assertEquals(0, animation.frameAt(0));
        assertEquals(1, animation.frameAt(150));
        assertEquals(2, animation.frameAt(450));
        assertEquals(0, animation.frameAt(650));
    }

    private static void frame(ImageWriter writer, Color color, int x, String disposal, int delay, int width)
            throws IOException {
        var image = new BufferedImage(width, 1, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < width; i++) image.setRGB(i, 0, color.getRGB());
        var metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), null);
        String format = metadata.getNativeMetadataFormatName();
        var root = (IIOMetadataNode) metadata.getAsTree(format);
        ((IIOMetadataNode) root.getElementsByTagName("ImageDescriptor").item(0))
                .setAttribute("imageLeftPosition", Integer.toString(x));
        var control = (IIOMetadataNode)
                root.getElementsByTagName("GraphicControlExtension").item(0);
        control.setAttribute("delayTime", Integer.toString(delay));
        control.setAttribute("disposalMethod", disposal);
        metadata.setFromTree(format, root);
        writer.writeToSequence(new IIOImage(image, null, metadata), null);
    }

    @Test
    void klipyMp4DecodesItsRealFrames() throws Exception {
        var result = ImageDecoder.decode(resource("/klipy-report.mp4"));
        assertEquals(256, result.width());
        assertEquals(192, result.height());
        assertEquals(44, result.frames().size());
        assertEquals(
                1760,
                result.frames().stream()
                        .mapToInt(ImageDecoder.Frame::durationMs)
                        .sum());
        var pixels = ByteBuffer.allocate(result.frames().getFirst().argb().length * 4);
        for (int pixel : result.frames().getFirst().argb()) pixels.putInt(pixel);
        // Pin the real animation's first frame so Klipy's "unavailable" card cannot pass.
        assertEquals(
                "fc875337ce28a9d0f909d87c9c9f0863059bf91ffe0ca457ee5ae2d8917e546c",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels.array())));
        assertFalse(Arrays.equals(
                result.frames().getFirst().argb(), result.frames().getLast().argb()));
    }

    @Test
    void unsupportedFormatsAreRejectedBeforeTheHelper() throws Exception {
        for (String text : List.of("<svg onload='attack()'/>", "<html>not a GIF</html>", "GIF90a fake", "PK fake"))
            assertThrows(IOException.class, () -> MediaFormat.identify(text.getBytes(StandardCharsets.UTF_8)), text);
        assertThrows(IOException.class, () -> MediaFormat.identify(new byte[] {1, 2, 3}));
        assertThrows(IOException.class, () -> MediaFormat.identify(new byte[MediaFormat.MAX_BYTES + 1]));
        var bmp = encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "bmp");
        assertThrows(IOException.class, () -> ImageDecoder.decode(bmp));
    }

    @Test
    void malformedMediaFailsWithoutInternalDetails() throws Exception {
        byte[] klipy = resource("/klipy-report.mp4");
        for (byte[] media : new byte[][] {
            {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, // PNG signature only
            ByteBuffer.allocate(16).putInt(Integer.MAX_VALUE).putInt(0x66747970).array(), // MP4 box size bomb
            ByteBuffer.allocate(36) // MP4 sample count bomb
                    .putInt(16)
                    .putInt(0x66747970)
                    .putLong(0)
                    .putInt(20)
                    .putInt(0x7374737a)
                    .putInt(0)
                    .putInt(1)
                    .putInt(Integer.MAX_VALUE)
                    .array(),
            Arrays.copyOf(klipy, 100), // truncated MP4
            encode(new BufferedImage(4097, 1, BufferedImage.TYPE_INT_ARGB), "png") // oversized
        }) {
            var failure = assertThrows(IOException.class, () -> ImageDecoder.decode(media));
            assertEquals("Image could not be decoded within safety limits", failure.getMessage());
        }
    }
}
