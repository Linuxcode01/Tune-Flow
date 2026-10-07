package com.tuneflow.storage.controller;

import com.tuneflow.common.response.ApiResponse;
import com.tuneflow.storage.dto.UploadResponse;
import com.tuneflow.storage.service.MediaUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Media upload endpoints for artists and admins (docs sections 16, 34). Uploads are rate limited
 * and role-restricted; the flow is:
 *
 * <pre>
 * 1. POST /api/v1/uploads/audio        -> { objectKey, previewUrl, durationSeconds }
 * 2. POST /api/v1/songs  (or PUT ...)  -> persist metadata + objectKey
 * </pre>
 */
@Tag(name = "Uploads", description = "Audio and artwork uploads (ARTIST/ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final MediaUploadService mediaUploadService;

    @Operation(summary = "Upload an audio file",
            description = "Validated, optionally transcoded with FFmpeg, then stored in S3/MinIO or local storage")
    @PreAuthorize("hasAnyRole('ARTIST','ADMIN')")
    @PostMapping(value = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UploadResponse>> uploadAudio(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success("Audio uploaded successfully",
                mediaUploadService.uploadAudio(file)));
    }

    @Operation(summary = "Upload artwork (album/song/artist/profile image)")
    @PreAuthorize("isAuthenticated()")
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UploadResponse>> uploadImage(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success("Image uploaded successfully",
                mediaUploadService.uploadImage(file)));
    }
}
