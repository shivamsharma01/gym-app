package com.example.gym.device.web;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.GatewayAuthService;
import com.example.gym.device.GatewayCommandPollService;
import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.GatewayService;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.dto.DesiredStateRequests.AcknowledgeRevision;
import com.example.gym.device.dto.DesiredStateRequests.DesiredPage;
import com.example.gym.device.dto.DesiredStateRequests.ReportOccupied;
import com.example.gym.device.dto.DesiredStateRequests.RevisionNotice;
import com.example.gym.device.dto.DeviceResponses.DeviceView;
import com.example.gym.device.dto.GatewayCredentialResponse;
import com.example.gym.device.dto.GatewayEnrollRequest;
import com.example.gym.face.GatewayFaceUpload;
import com.example.gym.face.MemberFaceService;
import com.example.gym.face.MemberFaceService.FaceImage;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST fallback of the gateway protocol (ingest + command poll) plus enrollment and credential
 * rotation. Live calls require the per-gateway operational credential. Enrollment exchanges a
 * one-time enrollment token; the gateway id on that request is not the identity. Never a user JWT.
 * There is no anonymous or shared-token path.
 */
@RestController
@RequestMapping("/internal/gateway")
public class InternalGatewayController {

    private final GatewayAuthService authService;
    private final GatewayService gatewayService;
    private final GatewayMessageService messageService;
    private final GatewayCommandPollService pollService;
    private final MemberFaceService faceService;
    private final DesiredProjectionService desiredProjection;

    public InternalGatewayController(GatewayAuthService authService,
                                     GatewayService gatewayService,
                                     GatewayMessageService messageService,
                                     GatewayCommandPollService pollService,
                                     MemberFaceService faceService,
                                     DesiredProjectionService desiredProjection) {
        this.authService = authService;
        this.gatewayService = gatewayService;
        this.messageService = messageService;
        this.pollService = pollService;
        this.faceService = faceService;
        this.desiredProjection = desiredProjection;
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
        Optional<String> reply = messageService.process(raw, gateway.getPublicId());
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

    @GetMapping(value = "/desired", produces = MediaType.APPLICATION_JSON_VALUE)
    public DesiredPage desired(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("deviceId") String deviceId,
            @RequestParam(value = "after", defaultValue = "0") long after,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return desiredProjection.pull(requireBoundGateway(authorization), deviceId, after, limit);
    }

    @PostMapping(value = "/desired/ack", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RevisionNotice acknowledgeDesired(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody AcknowledgeRevision ack) {
        return desiredProjection.acknowledge(requireBoundGateway(authorization), ack);
    }

    @PostMapping(value = "/desired/occupied", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RevisionNotice occupiedDesired(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ReportOccupied report) {
        return desiredProjection.occupied(requireBoundGateway(authorization), report);
    }

    private Gateway requireBoundGateway(String authorization) {
        return requireGateway(authorization);
    }

    /** The gateway that owns the presented operational credential. Missing or expired tokens fail. */
    private Gateway requireGateway(String authorization) {
        String token = bearer(authorization);
        return authService.authenticate(token)
                .orElseThrow(() -> CommonExceptions.unauthorized("Invalid or expired gateway token"));
    }

    private String bearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw CommonExceptions.unauthorized("Gateway bearer token required");
        }
        return authorization.substring(7).trim();
    }
}
