package com.example.gym.device.web;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.GatewayAuthService;
import com.example.gym.device.ReaderApplyStatusService;
import com.example.gym.device.ReaderOccupantService;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.dto.DesiredStateRequests.ObservationQuery;
import com.example.gym.device.dto.DesiredStateRequests.ObservationStatus;
import com.example.gym.device.dto.DesiredStateRequests.ReportFailed;
import com.example.gym.device.dto.DesiredStateRequests.ReportOccupant;
import com.example.gym.device.dto.DesiredStateRequests.RevisionNotice;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway's reports about applying desired state: a reader id holds something other than what
 * it was told to expect, a revision it could not apply, and whether its latest reader observation
 * has been processed.
 */
@RestController
@RequestMapping("/internal/gateway")
public class GatewayOccupantController {

    private final GatewayAuthService authService;
    private final ReaderOccupantService occupants;
    private final ReaderApplyStatusService applyStatus;

    public GatewayOccupantController(GatewayAuthService authService, ReaderOccupantService occupants,
                                     ReaderApplyStatusService applyStatus) {
        this.authService = authService;
        this.occupants = occupants;
        this.applyStatus = applyStatus;
    }

    @PostMapping(value = "/desired/occupant", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RevisionNotice occupantChanged(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ReportOccupant report) {
        return occupants.report(requireGateway(authorization), report);
    }

    @PostMapping(value = "/desired/observed", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ObservationStatus observed(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ObservationQuery query) {
        return applyStatus.observed(requireGateway(authorization), query);
    }

    @PostMapping(value = "/desired/failed", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RevisionNotice failed(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ReportFailed report) {
        return applyStatus.failed(requireGateway(authorization), report);
    }

    private Gateway requireGateway(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw CommonExceptions.unauthorized("Gateway bearer token required");
        }
        return authService.authenticate(authorization.substring(7).trim())
                .orElseThrow(() -> CommonExceptions.unauthorized("Invalid or expired gateway token"));
    }
}
