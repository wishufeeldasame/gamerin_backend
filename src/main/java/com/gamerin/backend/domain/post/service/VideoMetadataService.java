package com.gamerin.backend.domain.post.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VideoMetadataService {

    private static final int BOX_HEADER_SIZE = 8;
    private static final int EXTENDED_BOX_HEADER_SIZE = 16;

    public double readDurationSeconds(MultipartFile file) {
        try (InputStream inputStream = new BufferedInputStream(file.getInputStream())) {
            DurationInfo durationInfo = findMovieDuration(inputStream, file.getSize());
            if (durationInfo == null || durationInfo.timescale() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Video duration metadata could not be read. Use an MP4, MOV, or M4V file with valid metadata."
                );
            }
            return durationInfo.duration() / (double) durationInfo.timescale();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read video metadata.", ex);
        }
    }

    private DurationInfo findMovieDuration(InputStream inputStream, long remainingBytes) throws IOException {
        while (remainingBytes >= BOX_HEADER_SIZE) {
            BoxHeader header = readBoxHeader(inputStream, remainingBytes);
            long payloadSize = header.payloadSize();

            if ("moov".equals(header.type())) {
                return findMovieHeaderDuration(inputStream, payloadSize);
            }

            skipFully(inputStream, payloadSize);
            remainingBytes -= header.size();
        }
        return null;
    }

    private DurationInfo findMovieHeaderDuration(InputStream inputStream, long remainingBytes) throws IOException {
        while (remainingBytes >= BOX_HEADER_SIZE) {
            BoxHeader header = readBoxHeader(inputStream, remainingBytes);
            long payloadSize = header.payloadSize();

            if ("mvhd".equals(header.type())) {
                return readMovieHeaderDuration(inputStream, payloadSize);
            }

            skipFully(inputStream, payloadSize);
            remainingBytes -= header.size();
        }
        return null;
    }

    private DurationInfo readMovieHeaderDuration(InputStream inputStream, long payloadSize) throws IOException {
        if (payloadSize < 20) {
            throw new IOException("Invalid mvhd box size.");
        }

        int version = readUnsignedByte(inputStream);
        skipFully(inputStream, 3);

        if (version == 1) {
            if (payloadSize < 32) {
                throw new IOException("Invalid mvhd version 1 box size.");
            }
            skipFully(inputStream, 16);
            long timescale = readUnsignedInt(inputStream);
            long duration = readLong(inputStream);
            return new DurationInfo(duration, timescale);
        }

        skipFully(inputStream, 8);
        long timescale = readUnsignedInt(inputStream);
        long duration = readUnsignedInt(inputStream);
        return new DurationInfo(duration, timescale);
    }

    private BoxHeader readBoxHeader(InputStream inputStream, long parentRemainingBytes) throws IOException {
        byte[] header = inputStream.readNBytes(BOX_HEADER_SIZE);
        if (header.length < BOX_HEADER_SIZE) {
            throw new IOException("Unexpected end of video file.");
        }

        long size = readUnsignedInt(header, 0);
        String type = new String(header, 4, 4, StandardCharsets.US_ASCII);
        int headerSize = BOX_HEADER_SIZE;

        if (size == 1) {
            size = readLong(inputStream);
            headerSize = EXTENDED_BOX_HEADER_SIZE;
        } else if (size == 0) {
            size = parentRemainingBytes;
        }

        if (size < headerSize || size > parentRemainingBytes) {
            throw new IOException("Invalid MP4 box size.");
        }

        return new BoxHeader(type, size, headerSize);
    }

    private long readUnsignedInt(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readNBytes(4);
        if (bytes.length < 4) {
            throw new IOException("Unexpected end of video file.");
        }
        return readUnsignedInt(bytes, 0);
    }

    private long readUnsignedInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xff) << 24
                | ((long) bytes[offset + 1] & 0xff) << 16
                | ((long) bytes[offset + 2] & 0xff) << 8
                | ((long) bytes[offset + 3] & 0xff);
    }

    private long readLong(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readNBytes(8);
        if (bytes.length < 8) {
            throw new IOException("Unexpected end of video file.");
        }
        return ((long) bytes[0] & 0xff) << 56
                | ((long) bytes[1] & 0xff) << 48
                | ((long) bytes[2] & 0xff) << 40
                | ((long) bytes[3] & 0xff) << 32
                | ((long) bytes[4] & 0xff) << 24
                | ((long) bytes[5] & 0xff) << 16
                | ((long) bytes[6] & 0xff) << 8
                | ((long) bytes[7] & 0xff);
    }

    private int readUnsignedByte(InputStream inputStream) throws IOException {
        int value = inputStream.read();
        if (value < 0) {
            throw new IOException("Unexpected end of video file.");
        }
        return value;
    }

    private void skipFully(InputStream inputStream, long bytesToSkip) throws IOException {
        long remaining = bytesToSkip;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                if (inputStream.read() == -1) {
                    throw new IOException("Unexpected end of video file.");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private record BoxHeader(String type, long size, int headerSize) {

        private long payloadSize() {
            return size - headerSize;
        }
    }

    private record DurationInfo(long duration, long timescale) {
    }
}
