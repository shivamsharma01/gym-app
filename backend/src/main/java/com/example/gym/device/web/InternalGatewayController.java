package com.example.gym.device.web;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.GatewayAuthService;
import com.example.gym.device.GatewayAuthService.Kind;
import com.example.gym.device.GatewayAuthService.Outcome;
import com.example.gym.device.GatewayCommandPollService;
import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.Gateway;
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
 * REST fallback of the gateway protocol (ingest + command poll). Authenticated with the same
 * per-gateway token as the WSS handshake — never a user JWT. Used by the local simulator.
 */
@RestController
@RequestMapping("/internal/gateway")
public class InternalGatewayController {

    private final GatewayAuthService authService;
    private final GatewayMessageService messageService;
    private final GatewayCommandPollService pollService;

    public InternalGatewayController(GatewayAuthService authService,
                                     GatewayMessageService messageService,
                                     GatewayCommandPollService pollService) {
        this.authService = authService;
        this.messageService = messageService;
        this.pollService = pollService;
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
        Gateway gateway = requireGateway(authorization);
        if (gateway == null) {
            throw CommonExceptions.unauthorized("Per-gateway token required to poll commands");
        }
        return pollService.claimDue(gateway);
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
