package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The gateway is told which reader photos the server still needs to create undecided people. */
class GatewayFaceWantsIT extends AbstractReaderIT {

    private static final String FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String TO = "2027-03-31T23:59:59.0000000+05:30";
    private static final String MISSING = "a".repeat(64);

    private String readerId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("face-wants");
        readerId = createReader("Entrance");
    }

    @Test
    void onlyPhotosOfUndecidedPeopleThatAreNotOnTheServerAreWanted() throws Exception {
        String uploaded = uploadFace(Color.RED);
        trustedRoster(readerId,
                person("11", "Asha Rao", 0, FROM, TO, uploaded),
                person("12", "Ravi Iyer", 0, FROM, TO, MISSING),
                person("13", "No Photo", 0, FROM, TO, null));

        assertThat(wanted(readerId)).containsExactly(MISSING);
    }

    @Test
    void anUnknownReaderIsNotFound() throws Exception {
        mockMvc.perform(get("/internal/gateway/faces/wanted")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", "no-such-reader"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherCallerIsRefused() throws Exception {
        mockMvc.perform(get("/internal/gateway/faces/wanted")
                        .header("Authorization", "Bearer not-a-gateway")
                        .param("deviceId", readerId))
                .andExpect(status().isUnauthorized());
    }

    private List<String> wanted(String reader) throws Exception {
        JsonNode body = readJson(mockMvc.perform(get("/internal/gateway/faces/wanted")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", reader))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<String> hashes = new ArrayList<>();
        body.forEach(hash -> hashes.add(hash.asString()));
        return hashes;
    }
}
