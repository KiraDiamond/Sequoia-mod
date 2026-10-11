package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

class MediaTest {
    @Test
    void recognizesScreenshotGifWithoutDroppingQuery() {
        String url =
                "https://cdn.discordapp.com/attachments/725134391021666366/1556530112252346429/togif.gif?backend=b2";
        assertEquals(List.of(URI.create(url)), MediaLinks.find("Topdiamond2006: " + url));
    }

    @Test
    void supportsMultipleImagesAndMarkdownPunctuation() {
        assertEquals(
                2,
                MediaLinks.find("see <https://media.tenor.com/a.gif> (https://i.imgur.com/b.png).")
                        .size());
    }

    @Test
    void refusesUntrustedOrDisguisedDestinations() {
        for (String url :
                List.of(
                        "http://cdn.discordapp.com/a.gif",
                        "https://localhost/a.png",
                        "https://cdn.discordapp.com.evil.test/a.gif",
                        "https://cdn.discordapp.com@evil.test/a.gif",
                        "https://cdn.discordapp.com:444/a.gif",
                        "file:///image.png")) assertTrue(MediaLinks.find(url).isEmpty(), url);
    }

    @Test
    void preservesOrdinaryChatAndNonImages() {
        assertTrue(MediaLinks.find("hello https://github.com/KiraDiamond/AS7-Mod").isEmpty());
        assertTrue(MediaLinks.find("https://cdn.discordapp.com/a.mp4").isEmpty());
    }

    @Test
    void staticPngKeepsAspectRatioAndTransparency() throws Exception {
        var source = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);
        var animation = ImageDecoder.decode(bytes.toByteArray());
        assertEquals(256, animation.width());
        assertEquals(128, animation.height());
        assertEquals(1, animation.frames().size());
        assertEquals(0, animation.frames().getFirst().argb()[0]);
    }

    @Test
    void decodesGifTimingAndRestoresPreviousCanvas() throws Exception {
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

    @Test
    void rejectsInvalidAndOversizedImages() throws Exception {
        assertThrows(java.io.IOException.class, () -> ImageDecoder.decode(new byte[] {1, 2, 3}));
        var source = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_ARGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);
        assertThrows(java.io.IOException.class, () -> ImageDecoder.decode(bytes.toByteArray()));
    }

    private void frame(
            javax.imageio.ImageWriter writer,
            Color color,
            int x,
            String disposal,
            int delay,
            int width)
            throws Exception {
        var image = new BufferedImage(width, 1, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < width; i++) image.setRGB(i, 0, color.getRGB());
        var metadata =
                writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(image), null);
        String format = metadata.getNativeMetadataFormatName();
        var root = (IIOMetadataNode) metadata.getAsTree(format);
        var descriptor = (IIOMetadataNode) root.getElementsByTagName("ImageDescriptor").item(0);
        descriptor.setAttribute("imageLeftPosition", Integer.toString(x));
        var control =
                (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
        control.setAttribute("delayTime", Integer.toString(delay));
        control.setAttribute("disposalMethod", disposal);
        metadata.setFromTree(format, root);
        writer.writeToSequence(new javax.imageio.IIOImage(image, null, metadata), null);
    }
}
