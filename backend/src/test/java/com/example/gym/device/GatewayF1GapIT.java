package com.example.gym.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.GatewayStatus;
import com.example.gym.face.GatewayFaceUpload;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The three F1 follow-ups: a socket dies when its credential expires or is rotated, a gateway
 * cannot read another gateway's member face, and the handshake takes the credential from
 * Authorization rather than the URL.
 */
class GatewayF1GapIT extends AbstractIntegrationTest {

    @Autowired
    private GatewayAuthService authService;

    @Autowired
    private GatewayHandshakeInterceptor handshakeInterceptor;

    @Autowired
    private GatewayWebSocketHandler webSocketHandler;

    @Autowired
    private GatewaySessionRegistry sessionRegistry;

    private final List<WebSocketSession> opened = new ArrayList<>();

    private String staffToken;
    private String gatewayId;
    private String credential;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Gap Gym", "gap-gym");
        createUser(tenant.getId(), "gap-admin", "gap-admin@gym.local", "GYM_ADMIN");
        staffToken = tokenFor("gap-admin");
        String created = createGateway("Front desk");
        gatewayId = readJson(created).get("id").asString();
        credential = enroll(gatewayId, readJson(created).get("token").asString());
    }

    @AfterEach
    void closeSockets() {
        for (WebSocketSession session : opened) {
            sessionRegistry.invalidate(session);
        }
        opened.clear();
    }

    @Test
    void openSocketIsRejectedAfterCredentialExpiry() throws Exception {
        WebSocketSession session = openSession(gatewayId, credential);
        assertThat(sessionRegistry.send(gatewayId, "command")).isTrue();
        verify(session, times(1)).sendMessage(any(WebSocketMessage.class));

        var gateway = gatewayRepository.findByPublicId(gatewayId).orElseThrow();
        gateway.setTokenExpiresAt(Instant.now().minusSeconds(60));
        gatewayRepository.saveAndFlush(gateway);

        webSocketHandler.handleTextMessage(session, new org.springframework.web.socket.TextMessage(heartbeat()));
        webSocketHandler.handleTextMessage(session, new org.springframework.web.socket.TextMessage(syncResult()));

        var reloaded = gatewayRepository.findByPublicId(gatewayId).orElseThrow();
        assertThat(reloaded.getLastHeartbeatAt()).isNull();
        assertThat(reloaded.getStatus()).isEqualTo(GatewayStatus.UNKNOWN);
        assertThat(deviceSyncCommandRepository.count()).isZero();

        sessionRegistry.register(gatewayId, session);
        assertThat(sessionRegistry.send(gatewayId, "after-expiry")).isFalse();
        assertThat(sessionRegistry.isOnline(gatewayId)).isFalse();
        verify(session, times(1)).sendMessage(any(WebSocketMessage.class));

        assertThat(handshake(authorization(credential), null)).isFalse();
    }

    @Test
    void openSocketBoundToTheOldCredentialStopsAfterRotation() throws Exception {
        WebSocketSession oldSession = openSession(gatewayId, credential);
        assertThat(sessionRegistry.send(gatewayId, "before-rotate")).isTrue();

        String rotated = mockMvc.perform(post("/internal/gateway/credentials/rotate")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String credentialV2 = readJson(rotated).get("credential").asString();
        assertThat(credentialV2).isNotEqualTo(credential);

        webSocketHandler.handleTextMessage(oldSession, new org.springframework.web.socket.TextMessage(heartbeat()));
        var reloaded = gatewayRepository.findByPublicId(gatewayId).orElseThrow();
        assertThat(reloaded.getLastHeartbeatAt()).isNull();
        assertThat(sessionRegistry.send(gatewayId, "after-rotate")).isFalse();
        assertThat(sessionRegistry.isOnline(gatewayId)).isFalse();
        verify(oldSession, times(1)).sendMessage(any(WebSocketMessage.class));

        assertThat(handshake(authorization(credential), null)).isFalse();

        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk());

        WebSocketSession renewed = openSession(gatewayId, credentialV2);
        assertThat(sessionRegistry.send(gatewayId, "with-new-credential")).isTrue();
        webSocketHandler.handleTextMessage(renewed, new org.springframework.web.socket.TextMessage(heartbeat()));
        assertThat(gatewayRepository.findByPublicId(gatewayId).orElseThrow().getLastHeartbeatAt()).isNotNull();

        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk());
    }

    @Test
    void handshakeUsesAuthorizationAndRejectsAQueryCredential() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(authorization(credential), attributes)).isTrue();
        assertThat(attributes).containsEntry(GatewayHandshakeInterceptor.GATEWAY_ID_ATTR, gatewayId);
        assertThat(attributes).containsEntry(
                GatewayHandshakeInterceptor.CREDENTIAL_HASH_ATTR, authService.hash(credential));

        assertThat(handshake(queryToken(credential), new HashMap<>())).isFalse();

        MockHttpServletRequest both = authorization(credential);
        both.setParameter("token", credential);
        both.setQueryString("token=" + credential);
        assertThat(handshake(both, new HashMap<>())).isFalse();

        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk());
    }

    @Test
    void gatewayCannotReadAnotherGatewaysMemberFace() throws Exception {
        String otherCreated = createGateway("Side door");
        String otherId = readJson(otherCreated).get("id").asString();
        String otherCredential = enroll(otherId, readJson(otherCreated).get("token").asString());

        String deviceA = createDevice("Lane A", "10.1.0.1", gatewayId);
        String deviceB = createDevice("Lane B", "10.1.0.2", otherId);

        String memberOnB = createMember("OnlyB", "9101");
        uploadPhoto(memberOnB, jpeg(Color.RED, 400));
        unmap(memberOnB, deviceA);

        String faceOnB = mockMvc.perform(get("/internal/gateway/faces/" + memberOnB + "/1")
                        .header("Authorization", "Bearer " + otherCredential))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Face-Sha256"))
                .andReturn().getResponse().getHeader("X-Face-Sha256");

        mockMvc.perform(get("/internal/gateway/faces/" + memberOnB + "/1")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isForbidden());

        String memberOnA = createMember("OnlyA", "9102");
        uploadPhoto(memberOnA, jpeg(Color.BLUE, 400));
        unmap(memberOnA, deviceB);
        mockMvc.perform(get("/internal/gateway/faces/" + memberOnA + "/1")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk());
        mockMvc.perform(get("/internal/gateway/faces/" + memberOnA + "/1")
                        .header("Authorization", "Bearer " + otherCredential))
                .andExpect(status().isForbidden());

        String uploaded = mockMvc.perform(post("/internal/gateway/faces")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(jpeg(Color.GREEN, 300)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        GatewayFaceUpload upload = gatewayFaceUploadRepository.findByPublicId(
                readJson(uploaded).get("uploadId").asString()).orElseThrow();
        Long gatewayA = gatewayRepository.findByPublicId(gatewayId).orElseThrow().getId();
        assertThat(upload.getGatewayId()).isEqualTo(gatewayA);
        assertThat(upload.getSha256()).isNotEqualTo(faceOnB);

        String bareCreated = createGateway("No readers");
        String bareId = readJson(bareCreated).get("id").asString();
        String bareCredential = enroll(bareId, readJson(bareCreated).get("token").asString());
        mockMvc.perform(post("/internal/gateway/faces")
                        .header("Authorization", "Bearer " + bareCredential)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(new byte[] {1, 2, 3}))
                .andExpect(status().isForbidden());
    }

    private WebSocketSession openSession(String publicId, String token) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(GatewayHandshakeInterceptor.GATEWAY_ID_ATTR, publicId);
        attributes.put(GatewayHandshakeInterceptor.CREDENTIAL_HASH_ATTR, authService.hash(token));
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("s-" + opened.size() + "-" + publicId);
        when(session.isOpen()).thenReturn(true);
        opened.add(session);
        sessionRegistry.register(publicId, session);
        return session;
    }

    private boolean handshake(MockHttpServletRequest servlet, Map<String, Object> attributes) throws Exception {
        Map<String, Object> target = attributes == null ? new HashMap<>() : attributes;
        return handshakeInterceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                target);
    }

    private static MockHttpServletRequest authorization(String token) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/gateway");
        servlet.addHeader("Authorization", "Bearer " + token);
        return servlet;
    }

    private static MockHttpServletRequest queryToken(String token) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/gateway");
        servlet.setQueryString("token=" + token);
        servlet.setParameter("token", token);
        return servlet;
    }

    private String createGateway(String name) throws Exception {
        return mockMvc.perform(post("/api/v1/gateways")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String enroll(String id, String enrollmentToken) throws Exception {
        String body = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + id + "\",\"enrollmentToken\":\"" + enrollmentToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readJson(body).get("credential").asString();
    }

    private String createDevice(String name, String host, String ownerGatewayId) throws Exception {
        String created = mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"" + host + "\","
                                + "\"port\":37777,\"gatewayId\":\"" + ownerGatewayId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return readJson(created).get("id").asString();
    }

    private String createMember(String firstName, String code) throws Exception {
        return readJson(mockMvc.perform(post("/api/v1/members")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"" + firstName + "\",\"lastName\":\"Test\",\"memberCode\":\""
                                + code + "\",\"serialNumber\":\"" + code + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private void uploadPhoto(String memberId, byte[] bytes) throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/members/" + memberId + "/face")
                        .file(new MockMultipartFile("file", "face.jpg", "image/jpeg", bytes))
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    private void unmap(String memberPublicId, String devicePublicId) {
        Long memberId = memberRepository.findByPublicId(memberPublicId).orElseThrow().getId();
        Long deviceId = deviceRepository.findByPublicId(devicePublicId).orElseThrow().getId();
        var mapping = memberDeviceMappingRepository.findByDeviceIdAndMemberId(deviceId, memberId).orElseThrow();
        memberDeviceMappingRepository.delete(mapping);
        memberDeviceMappingRepository.flush();
    }

    private static String heartbeat() {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"body-gateway","deviceId":null,\
                "type":"HEARTBEAT","correlationId":"%s","payload":{}}
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());
    }

    private static String syncResult() {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"body-gateway","deviceId":"dev-foreign",\
                "type":"SYNC_RESULT","correlationId":"%s","payload":{"ok":true}}
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());
    }

    private static byte[] jpeg(Color color, int side) throws Exception {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, side, side);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
