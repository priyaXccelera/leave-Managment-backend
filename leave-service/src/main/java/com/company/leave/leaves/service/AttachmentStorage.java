package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse.FieldError;
import com.company.leave.leaves.config.LeaveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Stores leave attachments (pdf, png, jpg, jpeg) below {@code leave.upload-dir} under random file names.
 * The client supplied file name is never used for the storage path.
 */
@Component
public class AttachmentStorage {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentStorage.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** extension -> content types accepted for it. */
    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
            "pdf", "application/pdf",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg");

    private final Path baseDir;
    private final long maxBytes;

    public AttachmentStorage(LeaveProperties properties) {
        this.baseDir = Path.of(properties.uploadDir()).toAbsolutePath().normalize();
        this.maxBytes = properties.maxUploadMb() * BYTES_PER_MB;
    }

    /**
     * Validates and stores the file.
     *
     * @return the stored file's path relative to the upload directory
     * @throws ApiException 400 VALIDATION_ERROR for empty, oversized or disallowed files
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("Attachment must not be empty");
        }
        if (file.getSize() > maxBytes) {
            throw invalid("Attachment must not exceed " + (maxBytes / BYTES_PER_MB) + " MB");
        }
        String extension = extensionOf(file.getOriginalFilename());
        String expectedType = CONTENT_TYPE_BY_EXTENSION.get(extension);
        if (expectedType == null) {
            throw invalid("Attachment must be a pdf, png, jpg or jpeg file");
        }
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!expectedType.equals(declared)) {
            throw invalid("Attachment content type '" + declared + "' does not match its file extension");
        }
        try {
            if (!hasExpectedSignature(file, extension)) {
                throw invalid("Attachment content does not match its file type");
            }
            Files.createDirectories(baseDir);
            String name = UUID.randomUUID() + "." + extension;
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, baseDir.resolve(name));
            }
            return name;
        } catch (IOException ex) {
            LOG.error("Could not store attachment", ex);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "Attachment could not be stored");
        }
    }

    /**
     * @throws ApiException 404 when the path escapes the upload directory or the file does not exist
     */
    public Resource load(String relativePath) {
        Path file = resolve(relativePath);
        if (file == null || !Files.isRegularFile(file)) {
            throw ApiException.notFound("Attachment not found");
        }
        return new FileSystemResource(file);
    }

    /** Best-effort removal used when the surrounding database transaction fails. */
    public void delete(String relativePath) {
        Path file = resolve(relativePath);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            LOG.warn("Could not delete attachment {}", relativePath, ex);
        }
    }

    /** Content type derived from the stored file's extension; octet-stream if unknown. */
    public static String contentTypeOf(String relativePath) {
        String type = CONTENT_TYPE_BY_EXTENSION.get(extensionOf(relativePath));
        return type == null ? "application/octet-stream" : type;
    }

    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path resolved = baseDir.resolve(relativePath).normalize();
        return resolved.startsWith(baseDir) && !resolved.equals(baseDir) ? resolved : null;
    }

    private static boolean hasExpectedSignature(MultipartFile file, String extension) throws IOException {
        byte[] expected = switch (extension) {
            case "pdf" -> PDF_MAGIC;
            case "png" -> PNG_MAGIC;
            default -> JPEG_MAGIC;
        };
        byte[] head = new byte[expected.length];
        try (InputStream in = file.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            return read == head.length && Arrays.equals(head, expected);
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.validation(message, List.of(new FieldError("attachment", message)));
    }
}
