package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.ReaderLinkMatches.Match;
import com.example.gym.device.domain.Device;
import com.example.gym.device.dto.LinkMatchesRequest;
import com.example.gym.device.dto.ReviewItemView;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.tenant.TenantGuard;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff link, in one go, the people on a reader who match a member another reader already holds.
 * Staff see the list first and confirm it; only confirmed pairs that still match are linked. Each
 * link is the same decision as Link on the Review page: it writes the member to this reader under
 * the reader's own id, and is audited one by one.
 */
@Service
public class ReaderLinkService {

    private final DeviceRepository devices;
    private final ReaderLinkMatches matches;
    private final ReaderLinkStep step;
    private final ReviewDecisionAudit audit;

    public ReaderLinkService(DeviceRepository devices,
                             ReaderLinkMatches matches,
                             ReaderLinkStep step,
                             ReviewDecisionAudit audit) {
        this.devices = devices;
        this.matches = matches;
        this.step = step;
        this.audit = audit;
    }

    public record Linked(String deviceId, int linked, List<String> notLinked) {
    }

    @Transactional(readOnly = true)
    public List<Match> preview(String devicePublicId, Long tenantId) {
        return matches.find(reader(devicePublicId, tenantId));
    }

    public Linked link(String devicePublicId, LinkMatchesRequest request, Long tenantId) {
        Device reader = reader(devicePublicId, tenantId);
        Map<String, String> confirmed = new LinkedHashMap<>();
        for (LinkMatchesRequest.Pair pair : request.links()) {
            confirmed.put(pair.enrollmentId(), pair.memberId());
        }
        List<ReviewItemView> done = step.linkConfirmed(reader, confirmed, tenantId);
        Set<String> linked = new HashSet<>();
        for (ReviewItemView view : done) {
            audit.decided(view);
            linked.add(view.id());
        }
        List<String> notLinked = confirmed.keySet().stream().filter(id -> !linked.contains(id)).toList();
        FlowLog.info("device", "linked matches reader={} linked={} notLinked={}",
                reader.getPublicId(), linked.size(), notLinked.size());
        return new Linked(reader.getPublicId(), linked.size(), notLinked);
    }

    private Device reader(String devicePublicId, Long tenantId) {
        Device reader = devices.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(reader.getTenantId(), tenantId, "Device");
        if (reader.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        return reader;
    }
}
