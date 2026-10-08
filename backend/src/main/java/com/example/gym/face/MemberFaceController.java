package com.example.gym.face;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/members/{id}/face")
@Tag(name = "Members")
public class MemberFaceController {

    private final MemberFaceService faceService;

    public MemberFaceController(MemberFaceService faceService) {
        this.faceService = faceService;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MEMBER_UPDATE')")
    @Operation(summary = "Upload or replace the member photo. A desired-state reader gets a face revision; other readers get a photo command")
    public FaceView upload(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw CommonExceptions.badRequest("Photo file is required");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw CommonExceptions.badRequest("Photo could not be read");
        }
        return FaceView.from(faceService.upload(id, bytes, SecurityUtils.currentTenantId()));
    }

    @GetMapping(produces = MediaType.IMAGE_JPEG_VALUE)
    @PreAuthorize("hasAuthority('MEMBER_VIEW')")
    @Operation(summary = "The member's current face photo (JPEG)")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        return faceService.image(id, SecurityUtils.currentTenantId())
                .map(img -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.noCache().cachePrivate())
                        .eTag("\"" + img.sha256() + "\"")
                        .body(img.bytes()))
                .orElseThrow(() -> CommonExceptions.notFound("Face photo"));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MEMBER_UPDATE')")
    @Operation(summary = "Remove the member's face photo from the server and all devices")
    public void delete(@PathVariable String id) {
        faceService.delete(id, SecurityUtils.currentTenantId());
    }

    public record FaceView(int version, String sha256, String source, Instant changedAt) {
        static FaceView from(MemberFace f) {
            return new FaceView(f.getFaceVersion(), f.getSha256(), f.getSource().name(), f.getChangedAt());
        }
    }
}
