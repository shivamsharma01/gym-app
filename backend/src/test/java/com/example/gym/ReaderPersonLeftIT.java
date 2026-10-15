package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A person nobody decided on who is no longer on the reader is closed, and reopens if the id shows someone again. */
class ReaderPersonLeftIT extends AbstractReaderIT {

    private static final String FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String TO = "2026-12-31T23:59:59.0000000+05:30";

    private String readerId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("person-left");
        readerId = createReader("Exit");
    }

    @Test
    void aPersonMissingFromACompleteListIsClosedAndReopensWhenSeenAgain() throws Exception {
        String asha = person("1", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED));
        String ravi = person("2", "Ravi Iyer", 0, FROM, TO, uploadFace(Color.BLUE));
        trustedRoster(readerId, asha, ravi);

        trustedRoster(readerId, ravi);

        assertThat(enrollment("1").isResolved()).isTrue();
        assertThat(enrollment("1").getReviewStatus()).isEqualTo(PendingEnrollment.GONE);
        assertThat(enrollment("2").isResolved()).isFalse();

        trustedRoster(readerId, asha, ravi);

        assertThat(enrollment("1").isResolved()).isFalse();
        assertThat(enrollment("1").getReviewStatus()).isEqualTo(PendingEnrollment.PENDING);
    }

    @Test
    void anEmptyListClosesEveryUndecidedPerson() throws Exception {
        trustedRoster(readerId,
                person("1", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED)),
                person("2", "Ravi Iyer", 0, FROM, TO, uploadFace(Color.BLUE)));

        trustedRoster(readerId);

        assertThat(pendingEnrollmentRepository.findByDeviceIdAndResolvedFalse(readerPk(readerId))).isEmpty();
    }

    private PendingEnrollment enrollment(String deviceUserId) {
        return pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(readerPk(readerId), deviceUserId).orElseThrow();
    }
}
