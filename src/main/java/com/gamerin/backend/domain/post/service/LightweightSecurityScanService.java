package com.gamerin.backend.domain.post.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LightweightSecurityScanService {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final byte[] EICAR_SIGNATURE = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE"
            .getBytes(StandardCharsets.US_ASCII);

    public void assertFileClean(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }

        assertNotExecutableHeader(file);
        assertNoKnownMalwareSignature(file);
    }

    private void assertNotExecutableHeader(MultipartFile file) {
        byte[] header = readHeader(file, 8);
        if (startsWith(header, new byte[] {'M', 'Z'})
                || startsWith(header, new byte[] {0x7f, 'E', 'L', 'F'})
                || startsWith(header, new byte[] {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce})
                || startsWith(header, new byte[] {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xcf})
                || startsWith(header, new byte[] {(byte) 0xce, (byte) 0xfa, (byte) 0xed, (byte) 0xfe})
                || startsWith(header, new byte[] {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe})
                || startsWith(header, new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe})) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Executable files are not allowed.");
        }
    }

    private void assertNoKnownMalwareSignature(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            byte[] overlap = new byte[0];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                byte[] combined = new byte[overlap.length + read];
                System.arraycopy(overlap, 0, combined, 0, overlap.length);
                System.arraycopy(buffer, 0, combined, overlap.length, read);

                if (contains(combined, EICAR_SIGNATURE)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file failed security scan.");
                }

                int overlapLength = Math.min(EICAR_SIGNATURE.length - 1, combined.length);
                overlap = Arrays.copyOfRange(combined, combined.length - overlapLength, combined.length);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to scan uploaded file.", ex);
        }
    }

    private byte[] readHeader(MultipartFile file, int length) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(length);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file header.", ex);
        }
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean contains(byte[] value, byte[] pattern) {
        if (value.length < pattern.length) {
            return false;
        }

        for (int start = 0; start <= value.length - pattern.length; start++) {
            boolean matched = true;
            for (int offset = 0; offset < pattern.length; offset++) {
                if (value[start + offset] != pattern[offset]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }
}
