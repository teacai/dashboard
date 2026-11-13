package com.teacai.wiki;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class FaviconTest {
    @Test void containsTurquoiseSquaresWithTransparentWCutoutsAtEveryResolution() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/static/favicon.ico")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        var ico = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, ico.getShort());
        assertEquals(1, ico.getShort());
        int[] sizes = {16, 32, 48, 64, 128, 256};
        assertEquals(sizes.length, ico.getShort());
        for (int size : sizes) {
            assertEquals(size == 256 ? 0 : size, Byte.toUnsignedInt(ico.get()));
            assertEquals(size == 256 ? 0 : size, Byte.toUnsignedInt(ico.get()));
            assertEquals(0, ico.get()); // True-color image, no palette.
            assertEquals(0, ico.get());
            assertEquals(1, ico.getShort());
            assertEquals(32, ico.getShort());
            int length = ico.getInt(), offset = ico.getInt();
            var image = ImageIO.read(new ByteArrayInputStream(bytes, offset, length));
            assertNotNull(image);
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
            assertEquals(0xFF40E0D0, image.getRGB(0, 0));
            assertEquals(0xFF40E0D0, image.getRGB(size / 2, size - 1));
            assertEquals(0, image.getRGB(size / 4, (int) (size * 0.45)) >>> 24,
                    "The w must be transparent rather than painted white");
            boolean antialiased = false;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    antialiased |= alpha > 0 && alpha < 255;
                }
            }
            assertTrue(antialiased, "Cutout edges should be antialiased at " + size + "px");
        }
    }
}
