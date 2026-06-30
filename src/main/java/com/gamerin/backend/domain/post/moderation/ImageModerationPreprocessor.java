package com.gamerin.backend.domain.post.moderation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImageModerationPreprocessor {

    private static final int MAX_MODERATION_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_DIMENSION = 1024;
    private static final float JPEG_QUALITY = 0.85f;

    public String toDataUrl(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                return toOriginalDataUrlIfSupported(file);
            }

            return toJpegDataUrl(image);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read image file for moderation.", ex);
        }
    }

    public String toDataUrl(Path imagePath) {
        try (InputStream inputStream = Files.newInputStream(imagePath)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Extracted video frame could not be read.");
            }

            return toJpegDataUrl(image);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read extracted video frame.", ex);
        }
    }

    public String toDataUrl(BufferedImage image) {
        if (image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image frame could not be read for moderation.");
        }
        try {
            return toJpegDataUrl(image);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image frame could not be prepared for moderation.", ex);
        }
    }

    private String toOriginalDataUrlIfSupported(MultipartFile file) throws IOException {
        if (!isWebp(file)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file could not be read.");
        }
        if (file.getSize() > MAX_MODERATION_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file is too large for moderation.");
        }

        byte[] bytes = file.getBytes();
        return "data:image/webp;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String toJpegDataUrl(BufferedImage image) throws IOException {
        byte[] jpegBytes = writeJpeg(resizeToModerationSize(image));
        if (jpegBytes.length > MAX_MODERATION_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file is too large for moderation.");
        }

        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpegBytes);
    }

    private BufferedImage resizeToModerationSize(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longestSide = Math.max(width, height);
        double scale = longestSide > MAX_DIMENSION ? MAX_DIMENSION / (double) longestSide : 1.0;

        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        return target;
    }

    private byte[] writeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ByteArrayOutputStream fallbackOutput = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", fallbackOutput);
            return fallbackOutput.toByteArray();
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(imageOutputStream);

            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(JPEG_QUALITY);
            }

            writer.write(null, new IIOImage(image, null, null), writeParam);
            return outputStream.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private boolean isWebp(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).equals("image/webp")) {
            return true;
        }

        String filename = file.getOriginalFilename();
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".webp");
    }
}
