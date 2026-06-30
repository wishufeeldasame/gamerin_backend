package com.gamerin.backend.domain.post.service;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

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

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
public class AnimatedGifProcessor {

    private static final int MAX_GIF_BYTES = 20 * 1024 * 1024;
    private static final int MAX_GIF_DIMENSION = 6_000;
    private static final long MAX_GIF_PIXELS = 24_000_000L;
    private static final int MAX_GIF_FRAMES = 300;
    private static final long MAX_TOTAL_ANIMATION_PIXELS = 120_000_000L;
    private static final String GIF_IMAGE_METADATA_FORMAT = "javax_imageio_gif_image_1.0";
    private static final String GIF_STREAM_METADATA_FORMAT = "javax_imageio_gif_stream_1.0";

    static {
        ImageIO.setUseCache(false);
    }

    public MediaStorageService.PreparedMediaFile prepare(MultipartFile file) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        if (!writers.hasNext()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "GIF writer is not available.");
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            if (imageOutputStream == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "GIF output could not be created.");
            }

            writer.setOutput(imageOutputStream);
            writer.prepareWriteSequence(null);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            readFrames(file, (frame, delayCentiseconds, index, frameCount, loopCount) -> {
                writeFrame(writer, writeParam, frame, delayCentiseconds, index == 0, loopCount);
                imageOutputStream.flushBefore(imageOutputStream.getStreamPosition());
                if (outputStream.size() > MAX_GIF_BYTES) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Processed GIF file is too large.");
                }
            });
            writer.endWriteSequence();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file could not be processed.", ex);
        } finally {
            writer.dispose();
        }

        byte[] sanitizedBytes = outputStream.toByteArray();
        if (sanitizedBytes.length == 0 || sanitizedBytes.length > MAX_GIF_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Processed GIF file is invalid or too large.");
        }
        return new MediaStorageService.PreparedMediaFile(sanitizedBytes, ".gif");
    }

    public void validate(MultipartFile file) {
        readFrames(file, (frame, delayCentiseconds, index, frameCount, loopCount) -> {
            // Reading and compositing every frame validates the complete animation.
        });
    }

    public List<BufferedImage> extractModerationFrames(MultipartFile file) {
        List<BufferedImage> selectedFrames = new ArrayList<>(3);
        readFrames(file, (frame, delayCentiseconds, index, frameCount, loopCount) -> {
            if (index == 0 || index == frameCount / 2 || index == frameCount - 1) {
                selectedFrames.add(frame);
            }
        });
        return List.copyOf(selectedFrames);
    }

    public boolean isGif(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && contentType.equalsIgnoreCase("image/gif")) {
            return true;
        }
        String filename = file.getOriginalFilename();
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    private void readFrames(MultipartFile file, GifFrameConsumer consumer) {
        validateDeclarationAndSignature(file);

        try (InputStream inputStream = file.getInputStream();
             ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            if (imageInputStream == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file could not be read.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file could not be decoded.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInputStream, false, false);
                int frameCount = reader.getNumImages(true);
                if (frameCount < 2) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file must contain at least two frames.");
                }
                if (frameCount > MAX_GIF_FRAMES) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file contains too many frames.");
                }

                CanvasSize canvasSize = readCanvasSize(reader);
                validateDimensions(canvasSize.width(), canvasSize.height());
                long animationPixels = Math.multiplyExact(
                        Math.multiplyExact((long) canvasSize.width(), canvasSize.height()),
                        frameCount
                );
                if (animationPixels > MAX_TOTAL_ANIMATION_PIXELS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF animation is too large to process.");
                }

                int loopCount = readLoopCount(reader.getImageMetadata(0));
                BufferedImage canvas = new BufferedImage(
                        canvasSize.width(),
                        canvasSize.height(),
                        BufferedImage.TYPE_INT_ARGB
                );
                PreviousFrame previousFrame = PreviousFrame.empty();

                for (int index = 0; index < frameCount; index++) {
                    canvas = applyPreviousDisposal(canvas, previousFrame);
                    IIOMetadata metadata = reader.getImageMetadata(index);
                    FrameInfo frameInfo = readFrameInfo(
                            metadata,
                            reader.getWidth(index),
                            reader.getHeight(index)
                    );
                    validateFrameBounds(frameInfo, canvasSize);
                    BufferedImage rawFrame = reader.read(index);
                    if (rawFrame.getWidth() != frameInfo.width() || rawFrame.getHeight() != frameInfo.height()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF frame dimensions are inconsistent.");
                    }

                    BufferedImage restoreSnapshot = "restoreToPrevious".equals(frameInfo.disposalMethod())
                            ? copyImage(canvas)
                            : null;
                    Graphics2D graphics = canvas.createGraphics();
                    try {
                        graphics.setComposite(AlphaComposite.SrcOver);
                        graphics.drawImage(rawFrame, frameInfo.left(), frameInfo.top(), null);
                    } finally {
                        graphics.dispose();
                    }

                    consumer.accept(
                            copyImage(canvas),
                            Math.max(1, frameInfo.delayCentiseconds()),
                            index,
                            frameCount,
                            loopCount
                    );
                    previousFrame = new PreviousFrame(frameInfo, restoreSnapshot);
                }
            } finally {
                reader.dispose();
            }
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF animation dimensions are too large.", ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IOException | IndexOutOfBoundsException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file could not be processed.", ex);
        }
    }

    private void validateDeclarationAndSignature(MultipartFile file) {
        if (file.getSize() > MAX_GIF_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file must be 20MB or smaller.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".gif")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file extension is invalid.");
        }

        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank() && !contentType.equalsIgnoreCase("image/gif")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF content type does not match the file extension.");
        }

        try (InputStream inputStream = file.getInputStream()) {
            String signature = new String(inputStream.readNBytes(6), StandardCharsets.US_ASCII);
            if (!"GIF87a".equals(signature) && !"GIF89a".equals(signature)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file header is invalid.");
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF file header could not be read.", ex);
        }
    }

    private CanvasSize readCanvasSize(ImageReader reader) throws IOException {
        IIOMetadata metadata = reader.getStreamMetadata();
        if (metadata != null) {
            Node root = metadata.getAsTree(GIF_STREAM_METADATA_FORMAT);
            Node descriptor = findFirstNode(root, "LogicalScreenDescriptor");
            if (descriptor instanceof IIOMetadataNode metadataNode) {
                return new CanvasSize(
                        integerAttribute(metadataNode, "logicalScreenWidth", reader.getWidth(0)),
                        integerAttribute(metadataNode, "logicalScreenHeight", reader.getHeight(0))
                );
            }
        }
        return new CanvasSize(reader.getWidth(0), reader.getHeight(0));
    }

    private FrameInfo readFrameInfo(IIOMetadata metadata, int frameWidth, int frameHeight) {
        Node root = metadata.getAsTree(GIF_IMAGE_METADATA_FORMAT);
        Node descriptor = findFirstNode(root, "ImageDescriptor");
        int left = 0;
        int top = 0;
        if (descriptor instanceof IIOMetadataNode metadataNode) {
            left = integerAttribute(metadataNode, "imageLeftPosition", 0);
            top = integerAttribute(metadataNode, "imageTopPosition", 0);
        }

        Node controlExtension = findFirstNode(root, "GraphicControlExtension");
        int delay = 1;
        String disposalMethod = "none";
        if (controlExtension instanceof IIOMetadataNode metadataNode) {
            delay = integerAttribute(metadataNode, "delayTime", 1);
            String declaredDisposal = metadataNode.getAttribute("disposalMethod");
            if (declaredDisposal != null && !declaredDisposal.isBlank()) {
                disposalMethod = declaredDisposal;
            }
        }

        return new FrameInfo(left, top, frameWidth, frameHeight, delay, disposalMethod);
    }

    private int readLoopCount(IIOMetadata metadata) {
        Node root = metadata.getAsTree(GIF_IMAGE_METADATA_FORMAT);
        NodeList extensions = ((IIOMetadataNode) root).getElementsByTagName("ApplicationExtension");
        for (int index = 0; index < extensions.getLength(); index++) {
            Node node = extensions.item(index);
            if (node instanceof IIOMetadataNode metadataNode
                    && "NETSCAPE".equals(metadataNode.getAttribute("applicationID"))
                    && metadataNode.getUserObject() instanceof byte[] bytes
                    && bytes.length >= 3
                    && bytes[0] == 1) {
                return (bytes[1] & 0xff) | ((bytes[2] & 0xff) << 8);
            }
        }
        return 0;
    }

    private BufferedImage applyPreviousDisposal(BufferedImage canvas, PreviousFrame previousFrame) {
        FrameInfo frameInfo = previousFrame.frameInfo();
        if (frameInfo == null) {
            return canvas;
        }
        if ("restoreToPrevious".equals(frameInfo.disposalMethod()) && previousFrame.restoreSnapshot() != null) {
            return copyImage(previousFrame.restoreSnapshot());
        }
        if ("restoreToBackgroundColor".equals(frameInfo.disposalMethod())) {
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Clear);
                graphics.fillRect(frameInfo.left(), frameInfo.top(), frameInfo.width(), frameInfo.height());
            } finally {
                graphics.dispose();
            }
        }
        return canvas;
    }

    private void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_GIF_DIMENSION || height > MAX_GIF_DIMENSION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF dimensions are invalid or too large.");
        }
        if ((long) width * height > MAX_GIF_PIXELS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF frame must be 24 megapixels or smaller.");
        }
    }

    private void validateFrameBounds(FrameInfo frameInfo, CanvasSize canvasSize) {
        validateDimensions(frameInfo.width(), frameInfo.height());
        if (frameInfo.left() < 0 || frameInfo.top() < 0
                || (long) frameInfo.left() + frameInfo.width() > canvasSize.width()
                || (long) frameInfo.top() + frameInfo.height() > canvasSize.height()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF frame bounds are invalid.");
        }
    }

    private void writeFrame(
            ImageWriter writer,
            ImageWriteParam writeParam,
            BufferedImage frame,
            int delayCentiseconds,
            boolean firstFrame,
            int loopCount
    ) throws IOException {
        IIOMetadata metadata = writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(frame),
                writeParam
        );
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(GIF_IMAGE_METADATA_FORMAT);
        IIOMetadataNode controlExtension = getOrCreateChild(root, "GraphicControlExtension");
        controlExtension.setAttribute("disposalMethod", "doNotDispose");
        controlExtension.setAttribute("userInputFlag", "FALSE");
        controlExtension.setAttribute("delayTime", Integer.toString(Math.min(65_535, delayCentiseconds)));

        if (firstFrame) {
            IIOMetadataNode extensions = getOrCreateChild(root, "ApplicationExtensions");
            IIOMetadataNode loopExtension = new IIOMetadataNode("ApplicationExtension");
            loopExtension.setAttribute("applicationID", "NETSCAPE");
            loopExtension.setAttribute("authenticationCode", "2.0");
            loopExtension.setUserObject(new byte[] {
                    1,
                    (byte) (loopCount & 0xff),
                    (byte) ((loopCount >> 8) & 0xff)
            });
            extensions.appendChild(loopExtension);
        }

        metadata.setFromTree(GIF_IMAGE_METADATA_FORMAT, root);
        writer.writeToSequence(new IIOImage(frame, null, metadata), writeParam);
    }

    private IIOMetadataNode getOrCreateChild(IIOMetadataNode root, String name) {
        NodeList children = root.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (name.equals(child.getNodeName())) {
                return (IIOMetadataNode) child;
            }
        }
        IIOMetadataNode child = new IIOMetadataNode(name);
        root.appendChild(child);
        return child;
    }

    private Node findFirstNode(Node root, String name) {
        if (name.equals(root.getNodeName())) {
            return root;
        }
        NodeList children = root.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node found = findFirstNode(children.item(index), name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private int integerAttribute(IIOMetadataNode node, String name, int defaultValue) {
        String value = node.getAttribute(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIF metadata is invalid.", ex);
        }
    }

    private BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    @FunctionalInterface
    private interface GifFrameConsumer {
        void accept(BufferedImage frame, int delayCentiseconds, int index, int frameCount, int loopCount)
                throws IOException;
    }

    private record CanvasSize(int width, int height) {
    }

    private record FrameInfo(
            int left,
            int top,
            int width,
            int height,
            int delayCentiseconds,
            String disposalMethod
    ) {
    }

    private record PreviousFrame(FrameInfo frameInfo, BufferedImage restoreSnapshot) {
        private static PreviousFrame empty() {
            return new PreviousFrame(null, null);
        }
    }
}
