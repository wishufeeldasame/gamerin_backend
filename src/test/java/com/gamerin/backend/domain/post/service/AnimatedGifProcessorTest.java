package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class AnimatedGifProcessorTest {

    private static final String GIF_METADATA_FORMAT = "javax_imageio_gif_image_1.0";

    private final AnimatedGifProcessor processor = new AnimatedGifProcessor();

    @Test
    void preparesAnimatedGifWithoutFlatteningAnimation() throws Exception {
        MockMultipartFile file = gifFile(animatedGifBytes());

        MediaStorageService.PreparedMediaFile prepared = processor.prepare(file);

        assertThat(prepared.extension()).isEqualTo(".gif");
        assertThat(prepared.bytes()).startsWith("GIF".getBytes());
        assertThat(frameCount(prepared.bytes())).isEqualTo(2);
        assertThat(processor.extractModerationFrames(file)).hasSize(2);
    }

    @Test
    void rejectsSingleFrameGifBecausePolicyIsAnimationOnly() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(frame(Color.RED), "gif", output);

        assertThatThrownBy(() -> processor.prepare(gifFile(output.toByteArray())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least two frames");
    }

    @Test
    void rejectsGifContentTypeMismatch() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "animation.gif",
                "text/html",
                animatedGifBytes()
        );

        assertThatThrownBy(() -> processor.prepare(file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("content type");
    }

    @Test
    void rejectsInvalidGifMagicBytes() {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "animation.gif",
                "image/gif",
                "<html>not a gif</html>".getBytes()
        );

        assertThatThrownBy(() -> processor.prepare(file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("header");
    }

    @Test
    void rejectsGifWithTooManyFrames() throws Exception {
        assertThatThrownBy(() -> processor.prepare(gifFile(animatedGifBytes(301, 8, 8))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("too many frames");
    }

    @Test
    void acceptsGifAtMaximumFrameBoundary() throws Exception {
        MediaStorageService.PreparedMediaFile prepared =
                processor.prepare(gifFile(animatedGifBytes(300, 8, 8)));

        assertThat(frameCount(prepared.bytes())).isEqualTo(300);
    }

    @Test
    void rejectsGifWithOversizedDimensions() throws Exception {
        assertThatThrownBy(() -> processor.prepare(gifFile(animatedGifBytes(2, 6_001, 1))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    void acceptsCaseInsensitiveExtensionAndContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "ANIMATION.GIF",
                "IMAGE/GIF",
                animatedGifBytes()
        );

        assertThatCode(() -> processor.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void enforcesGifFileSizeBoundaryBeforeReadingBody() throws Exception {
        byte[] animation = animatedGifBytes();
        MultipartFile exactLimit = multipartFileWithReportedSize(animation, 20L * 1024L * 1024L);
        MultipartFile overLimit = multipartFileWithReportedSize(animation, 20L * 1024L * 1024L + 1L);

        assertThatCode(() -> processor.validate(exactLimit)).doesNotThrowAnyException();
        assertThatThrownBy(() -> processor.validate(overLimit))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("20MB or smaller");
    }

    @Test
    void rejectsTruncatedGifWithoutLeakingParserException() throws Exception {
        byte[] animation = animatedGifBytes();
        byte[] truncated = Arrays.copyOf(animation, 20);

        assertThatThrownBy(() -> processor.prepare(gifFile(truncated)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GIF file");
    }

    @Test
    void rejectsDeterministicMalformedGifCorpusWithControlledResponse() {
        Random random = new Random(27L);
        for (int index = 0; index < 25; index++) {
            byte[] malformed = new byte[32 + index * 7];
            random.nextBytes(malformed);
            System.arraycopy("GIF89a".getBytes(), 0, malformed, 0, 6);

            assertThatThrownBy(() -> processor.prepare(gifFile(malformed)))
                    .as("malformed GIF corpus entry %s", index)
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void rejectsTotalAnimationPixelBombBeforeAllocatingCanvas() throws Exception {
        byte[] animation = animatedGifBytes(7, 8, 8);
        setLittleEndianShort(animation, 6, 5_000);
        setLittleEndianShort(animation, 8, 4_000);

        assertThatThrownBy(() -> processor.prepare(gifFile(animation)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("too large to process");
    }

    @Test
    void moderationFramesRepresentFirstMiddleAndLastCompositedFrames() throws Exception {
        MockMultipartFile file = gifFile(animatedGifBytes(Color.RED, Color.GREEN, Color.BLUE));

        List<BufferedImage> frames = processor.extractModerationFrames(file);

        assertThat(frames).hasSize(3);
        assertThat(rgb(frames.get(0))).isEqualTo(rgb(Color.RED));
        assertThat(rgb(frames.get(1))).isEqualTo(rgb(Color.GREEN));
        assertThat(rgb(frames.get(2))).isEqualTo(rgb(Color.BLUE));
    }

    private MockMultipartFile gifFile(byte[] bytes) {
        return new MockMultipartFile("mediaFiles", "animation.gif", "image/gif", bytes);
    }

    private byte[] animatedGifBytes() throws IOException {
        return animatedGifBytes(2, 8, 8);
    }

    private byte[] animatedGifBytes(Color... colors) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.prepareWriteSequence(null);
            for (Color color : colors) {
                writeFrame(writer, frame(color), 5);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private byte[] animatedGifBytes(int frameCount, int width, int height) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.prepareWriteSequence(null);
            for (int index = 0; index < frameCount; index++) {
                writeFrame(writer, frame(index % 2 == 0 ? Color.RED : Color.BLUE, width, height), 5);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private void writeFrame(ImageWriter writer, BufferedImage image, int delay) throws IOException {
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        IIOMetadata metadata = writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(image),
                writeParam
        );
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(GIF_METADATA_FORMAT);
        IIOMetadataNode control = findNode(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "doNotDispose");
        control.setAttribute("delayTime", Integer.toString(delay));
        metadata.setFromTree(GIF_METADATA_FORMAT, root);
        writer.writeToSequence(new IIOImage(image, null, metadata), writeParam);
    }

    private BufferedImage frame(Color color) {
        return frame(color, 8, 8);
    }

    private BufferedImage frame(Color color, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private int frameCount(byte[] gifBytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes))) {
            ImageReader reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input, false, false);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        }
    }

    private MultipartFile multipartFileWithReportedSize(byte[] bytes, long reportedSize) throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(reportedSize);
        when(file.getOriginalFilename()).thenReturn("animation.gif");
        when(file.getContentType()).thenReturn("image/gif");
        when(file.getInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return file;
    }

    private void setLittleEndianShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    private int rgb(BufferedImage image) {
        return image.getRGB(0, 0) & 0x00ffffff;
    }

    private int rgb(Color color) {
        return color.getRGB() & 0x00ffffff;
    }

    private IIOMetadataNode findNode(IIOMetadataNode root, String name) {
        if (name.equals(root.getNodeName())) {
            return root;
        }
        NodeList children = root.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof IIOMetadataNode metadataNode) {
                IIOMetadataNode found = findNode(metadataNode, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
