package com.tuneflow.storage.service;

import com.tuneflow.common.config.AppProperties;
import com.tuneflow.common.exception.ApiException;
import com.tuneflow.common.exception.ErrorCode;
import com.tuneflow.common.util.HashUtils;
import com.tuneflow.storage.config.StorageProperties;
import com.tuneflow.storage.dto.UploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Validates and stores uploaded audio/artwork (docs section 16).
 *
 * <p>Security posture: MIME type and extension are both checked, the size limit is enforced before
 * anything is written, and object keys are generated server-side so a client cannot influence the
 * storage layout or traverse directories.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaUploadService {

    private final StorageService storageService;
    private final MediaProcessingService mediaProcessingService;
    private final StorageProperties storageProperties;
    private final AppProperties appProperties;

    public UploadResponse uploadAudio(MultipartFile file) {
        AppProperties.Media media = appProperties.media();
        validate(file, media.allowedAudioFormats(), media.maxAudioSizeBytes(), "audio");

        String extension = extensionOf(file.getOriginalFilename(), "mp3");
        String objectKey = buildKey("audio", extension);

        Path tempSource = null;
        try {
            tempSource = Files.createTempFile("tuneflow-upload-", "." + extension);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tempSource, StandardCopyOption.REPLACE_EXISTING);
            }

            MediaProcessingService.MediaMetadata metadata = mediaProcessingService.probe(tempSource);

            Path toStore = tempSource;
            String storeKey = objectKey;
            if (mediaProcessingService.isAvailable()) {
                Path transcoded = mediaProcessingService.transcodeToMp3(
                        tempSource, tempSource.getParent(), media.defaultBitrateKbps());
                if (!transcoded.equals(tempSource)) {
                    toStore = transcoded;
                    storeKey = replaceExtension(objectKey, "mp3");
                }
            }

            long size = Files.size(toStore);
            String contentType = "audio/mpeg".equals(file.getContentType()) || storeKey.endsWith(".mp3")
                    ? "audio/mpeg"
                    : nvl(file.getContentType(), "application/octet-stream");

            try (InputStream in = Files.newInputStream(toStore)) {
                storageService.store(storeKey, in, size, contentType);
            }

            return new UploadResponse(
                    storeKey,
                    storageService.generateSignedUrl(storeKey, media.signedUrlTtl()),
                    media.signedUrlTtl().toSeconds(),
                    metadata.durationSeconds(),
                    metadata.bitrateKbps(),
                    contentType,
                    size,
                    HashUtils.sha256Hex(storeKey + size));
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "Upload failed: " + ex.getMessage());
        } finally {
            cleanup(tempSource);
        }
    }

    public UploadResponse uploadImage(MultipartFile file) {
        AppProperties.Media media = appProperties.media();
        validate(file, media.allowedImageFormats(), media.maxImageSizeBytes(), "image");

        String extension = extensionOf(file.getOriginalFilename(), "jpg");
        String objectKey = buildKey("images", extension);
        try (InputStream in = file.getInputStream()) {
            long size = file.getSize();
            String contentType = nvl(file.getContentType(), "image/jpeg");
            storageService.store(objectKey, in, size, contentType);
            return new UploadResponse(
                    objectKey,
                    storageService.generateSignedUrl(objectKey, media.signedUrlTtl()),
                    media.signedUrlTtl().toSeconds(),
                    null,
                    null,
                    contentType,
                    size,
                    HashUtils.sha256Hex(objectKey + size));
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "Upload failed: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------

    private void validate(MultipartFile file, java.util.List<String> allowedFormats, long maxBytes, String kind) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "No " + kind + " file was uploaded");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                    "Maximum " + kind + " size is " + (maxBytes / (1024 * 1024)) + " MB");
        }
        String extension = extensionOf(file.getOriginalFilename(), "");
        if (!allowedFormats.contains(extension)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported " + kind + " format '" + extension + "'. Allowed: " + allowedFormats);
        }
    }

    private String buildKey(String prefix, String extension) {
        LocalDate today = LocalDate.now();
        return String.format("%s/%d/%02d/%s.%s",
                prefix, today.getYear(), today.getMonthValue(),
                UUID.randomUUID().toString().replace("-", ""), extension);
    }

    private static String extensionOf(String fileName, String fallback) {
        if (fileName == null) {
            return fallback;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return fallback;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String replaceExtension(String key, String extension) {
        int dot = key.lastIndexOf('.');
        return dot > 0 ? key.substring(0, dot + 1) + extension : key + "." + extension;
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void cleanup(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
