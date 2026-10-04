package com.example.gym.device.web;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.GatewayAuthService;
import com.example.gym.device.GatewayAuthService.Kind;
import com.example.gym.device.GatewayAuthService.Outcome;
import com.example.gym.device.GatewayCommandPollService;
import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.GatewayService;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.dto.DeviceResponses.DeviceView;
import com.example.gym.device.dto.GatewayCredentialResponse;
import com.example.gym.device.dto.GatewayEnrollRequest;
import com.example.gym.face.GatewayFaceUpload;
import com.example.gym.face.MemberFaceService;
import com.example.gym.face.MemberFaceService.FaceImage;
import java.util.ArrayList;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST fallback of the gateway protocol (ingest + command poll) plus enrollment and credential
 * rotation. Authenticated with the per-gateway operational credential (or enrollment for enroll)
 * — never a user JWT.
 */
@RestController
@RequestMapping("/internal/gateway")
public class InternalGatewayController {

    private final GatewayAuthService authService;
    private final GatewayService gatewayService;
    private final GatewayMessageService messageService;
    private static final int MAX_FACE_BATCH = 20;
    private static final long MAX_FACE_BATCH_BYTES = 10L * 1024 * 1024;

    private final GatewayCommandPollService pollService;
    private final MemberFaceService faceService;
    private final JsonMapper jsonMapper;

    public InternalGatewayController(GatewayAuthService authService,
                                     GatewayService gatewayService,
                                     GatewayMessageService messageService,
                                     GatewayCommandPollService pollService,
                                     MemberFaceService faceService,
                                     JsonMapper jsonMapper) {
        this.authService = authService;
        this.gatewayService = gatewayService;
        this.messageService = messageService;
        this.pollService = pollService;
        this.faceService = faceService;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping(value = "/enroll", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public GatewayCredentialResponse enroll(@Valid @RequestBody GatewayEnrollRequest request) {
        return gatewayService.enroll(request.gatewayId(), request.enrollmentToken());
    }

    @PostMapping(value = "/credentials/rotate", produces = MediaType.APPLICATION_JSON_VALUE)
    public GatewayCredentialResponse rotate(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Gateway gateway = requireBoundGateway(authorization);
        return gatewayService.rotate(gateway);
    }

    @GetMapping(value = "/devices", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<DeviceView> devices(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Gateway gateway = requireBoundGateway(authorization);
        return gatewayService.listDevicesForGateway(gateway);
    }

    @PostMapping(value = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public String ingest(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestBody String raw) {
        Gateway gateway = requireGateway(authorization);
        String boundId = gateway == null ? null : gateway.getPublicId();
        Optional<String> reply = messageService.process(raw, boundId);
        return reply.orElse("{}");
    }

    @GetMapping("/commands")
    public List<Map<String, Object>> poll(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Gateway gateway = requireBoundGateway(authorization);
        return pollService.claimDue(gateway);
    }

    /** Face image for UPSERT_FACE (images never travel inside WebSocket frames). */
    @GetMapping(value = "/faces/{memberId}/{version}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> face(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String memberId, @PathVariable int version) {
        Gateway gateway = requireBoundGateway(authorization);
        FaceImage image = faceService.imageForGateway(gateway, memberId, version);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header("X-Face-Sha256", image.sha256())
                .body(image.bytes());
    }

    /** A face image the gateway read from a device; referenced later by DEVICE_USER_CHANGED. */
    @PostMapping(value = "/faces", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> uploadFace(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody byte[] body) {
        Gateway gateway = requireBoundGateway(authorization);
        GatewayFaceUpload upload = faceService.acceptGatewayUpload(gateway, body);
        return Map.of("uploadId", upload.getPublicId(), "sha256", upload.getSha256());
    }

    /**
     * Batch version of /faces. The metadata array and files are positionally aligned:
     * [{"deviceUserId":"1001"}, ...] + repeated "faces" multipart parts.
     * Each image still becomes its own GatewayFaceUpload row, but the HTTP request and DB
     * transaction are shared.
     */
    @PostMapping(value = "/faces/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> uploadFacesBatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestPart("metadata") String metadata,
            @RequestPart("faces") List<MultipartFile> files) {
        Gateway gateway = requireBoundGateway(authorization);

        if (files == null || files.isEmpty()) {
            throw CommonExceptions.badRequest("At least one face is required");
        }
        if (files.size() > MAX_FACE_BATCH) {
            throw CommonExceptions.badRequest("Face batch is limited to " + MAX_FACE_BATCH + " images");
        }

        JsonNode meta;
        try {
            meta = jsonMapper.readTree(metadata);
        } catch (RuntimeException ex) {
            throw CommonExceptions.badRequest("Invalid face batch metadata");
        }

        if (meta == null || !meta.isArray() || meta.size() != files.size()) {
            throw CommonExceptions.badRequest("Face metadata count does not match image count");
        }

        long totalBytes = 0;
        List<byte[]> images = new ArrayList<>(files.size());
        List<String> userIds = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) {
                throw CommonExceptions.badRequest("Face " + i + " is empty");
            }
            totalBytes += file.getSize();
            if (totalBytes > MAX_FACE_BATCH_BYTES) {
                throw CommonExceptions.badRequest("Face batch exceeds 10 MB");
            }

            JsonNode item = meta.get(i);
            String deviceUserId = item == null || item.get("deviceUserId") == null
                    ? null : item.get("deviceUserId").asText();
            if (!StringUtils.hasText(deviceUserId)) {
                throw CommonExceptions.badRequest("deviceUserId is required for face " + i);
            }

            try {
                images.add(file.getBytes());
            } catch (java.io.IOException ex) {
                throw CommonExceptions.badRequest("Could not read face " + i);
            }
            userIds.add(deviceUserId.trim());
        }

        List<GatewayFaceUpload> uploads = faceService.acceptGatewayUploads(gateway, images);
        List<Map<String, Object>> items = new ArrayList<>(uploads.size());
        for (int i = 0; i < uploads.size(); i++) {
            GatewayFaceUpload upload = uploads.get(i);
            items.add(Map.of(
                    "index", i,
                    "deviceUserId", userIds.get(i),
                    "uploadId", upload.getPublicId(),
                    "sha256", upload.getSha256(),
                    "sizeBytes", upload.getSizeBytes()));
        }

        return Map.of("count", items.size(), "items", items);
    }

    private Gateway requireBoundGateway(String authorization) {
        Gateway gateway = requireGateway(authorization);
        if (gateway == null) {
            throw CommonExceptions.unauthorized("Per-gateway credential required");
        }
        return gateway;
    }

    /** @return the bound gateway, or null when authenticated via the deployment shared token. */
    private Gateway requireGateway(String authorization) {
        String token = bearer(authorization);
        Outcome outcome = authService.authenticate(token)
                .orElseThrow(() -> CommonExceptions.unauthorized("Invalid gateway token"));
        if (outcome.kind() == Kind.GATEWAY) {
            return outcome.gateway();
        }
        return null;
    }

    private String bearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw CommonExceptions.unauthorized("Gateway bearer token required");
        }
        return authorization.substring(7).trim();
    }
}
