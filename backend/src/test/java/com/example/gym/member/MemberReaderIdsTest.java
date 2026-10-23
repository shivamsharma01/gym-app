package com.example.gym.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gym.member.dto.MemberResponse.ReaderUserId;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberReaderIdsTest {

    @Test
    void idsDifferOnlyWhenTwoKnownReaderIdsDisagree() {
        ReaderUserId entry = new ReaderUserId("entry", "Entry", "9", "ok");
        ReaderUserId same = new ReaderUserId("exit", "Exit", "9", "seen");
        ReaderUserId other = new ReaderUserId("exit", "Exit", "12", "seen");
        ReaderUserId missing = new ReaderUserId("exit", "Exit", null, "none");

        assertThat(MemberReaderIds.idsDiffer(List.of(entry, same))).isFalse();
        assertThat(MemberReaderIds.idsDiffer(List.of(entry, other))).isTrue();
        assertThat(MemberReaderIds.idsDiffer(List.of(entry, missing))).isFalse();
        assertThat(MemberReaderIds.idsDiffer(List.of(missing))).isFalse();
    }
}
