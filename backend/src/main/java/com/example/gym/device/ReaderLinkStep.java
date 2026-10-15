package com.example.gym.device;

import com.example.gym.device.ReaderLinkMatches.Match;
import com.example.gym.device.domain.Device;
import com.example.gym.device.dto.ReviewItemView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links the pairs staff confirmed from a preview. Every reader of the gym is locked first, the
 * matches are worked out again, and a pair is linked only when it is still a match to the same
 * member. A pair that stopped matching since the preview is left for the Review page.
 */
@Service
public class ReaderLinkStep {

    private final ReaderReconcileService readers;
    private final ReaderLinkMatches matches;
    private final ReviewDecisionService decisions;

    public ReaderLinkStep(ReaderReconcileService readers, ReaderLinkMatches matches, ReviewDecisionService decisions) {
        this.readers = readers;
        this.matches = matches;
        this.decisions = decisions;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ReviewItemView> linkConfirmed(Device reader, Map<String, String> confirmed, Long tenantId) {
        readers.lockReaders(tenantId);
        List<ReviewItemView> linked = new ArrayList<>();
        for (Match match : matches.find(reader)) {
            if (match.memberId().equals(confirmed.get(match.enrollmentId()))) {
                linked.add(decisions.link(match.enrollmentId(), match.memberId(), tenantId));
            }
        }
        return linked;
    }
}
