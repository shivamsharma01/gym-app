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
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final GatewayCommandPollService pollService;

    public InternalGatewayController(GatewayAuthService authService,
                                     GatewayService gatewayService,
                                     GatewayMessageService messageService,
                                     GatewayCommandPollService pollService) {
        this.authService = authService;
        this.gatewayService = gatewayService;
        this.messageService = messageService;
        this.pollService = pollService;
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
