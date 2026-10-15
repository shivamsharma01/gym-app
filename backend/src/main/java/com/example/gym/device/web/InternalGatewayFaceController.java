package com.example.gym.device.web;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.GatewayAuthService;
import com.example.gym.device.GatewayFaceWants;
import com.example.gym.device.domain.Gateway;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Which reader photos the gateway should upload. Requires the gateway's operational credential. */
@RestController
@RequestMapping("/internal/gateway")
public class InternalGatewayFaceController {

    private final GatewayAuthService authService;
    private final GatewayFaceWants wants;

    public InternalGatewayFaceController(GatewayAuthService authService, GatewayFaceWants wants) {
        this.authService = authService;
        this.wants = wants;
    }

    /** SHA-256 of each photo of an undecided person on this reader that the server does not hold. */
    @GetMapping(value = "/faces/wanted", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> wanted(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("deviceId") String deviceId) {
        return wants.wanted(requireGateway(authorization), deviceId);
    }

    private Gateway requireGateway(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw CommonExceptions.unauthorized("Gateway bearer token required");
        }
        return authService.authenticate(authorization.substring(7).trim())
                .orElseThrow(() -> CommonExceptions.unauthorized("Invalid or expired gateway token"));
    }
}
