package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Member list search (name with spaces) and the Member / Serial column sorts. */
class MemberListIT extends AbstractIntegrationTest {

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("List Gym", "list-gym");
        createUser(tenant.getId(), "list-admin", "list-admin@list.local", "GYM_ADMIN");
        token = tokenFor("list-admin");

        createMember("Kunal", "Sharma", "10");
        createMember("Kunalpreet", "Singh", "2");
        createMember("Asha", null, "1");
        String legacy = createMember("Old", "Member", "500");
        var member = memberRepository.findByPublicId(legacy).orElseThrow();
        member.setSerialNumber(null);
        memberRepository.save(member);
    }

    @Test
    void aTrailingSpaceNarrowsTheSearchToThatFirstName() throws Exception {
        assertThat(names("kunal")).containsExactlyInAnyOrder("Kunal Sharma", "Kunalpreet Singh");
        assertThat(names("kunal ")).containsExactly("Kunal Sharma");
        assertThat(names("  Kunal   sh")).containsExactly("Kunal Sharma");
        assertThat(names("kunal sharma ")).containsExactly("Kunal Sharma");
        assertThat(names("asha ")).containsExactly("Asha");
        assertThat(names("   ")).hasSize(4);
        assertThat(names(" 10 ")).containsExactly("Kunal Sharma");
    }

    @Test
    void serialSortsAsANumberWithMembersWithoutASerialLast() throws Exception {
        assertThat(column("serial", "asc", "serialNumber")).containsExactly("1", "2", "10", null);
        assertThat(column("serial", "desc", "serialNumber")).containsExactly("10", "2", "1", null);
    }

    @Test
    void memberSortsByName() throws Exception {
        assertThat(column("name", "asc", "fullName"))
                .containsExactly("Asha", "Kunal Sharma", "Kunalpreet Singh", "Old Member");
        assertThat(column("name", "desc", "fullName"))
                .containsExactly("Old Member", "Kunalpreet Singh", "Kunal Sharma", "Asha");
    }

    private String createMember(String first, String last, String serial) throws Exception {
        String body = last == null
                ? "{\"firstName\":\"%s\",\"serialNumber\":\"%s\"}".formatted(first, serial)
                : "{\"firstName\":\"%s\",\"lastName\":\"%s\",\"serialNumber\":\"%s\"}".formatted(first, last, serial);
        return readJson(mockMvc.perform(post("/api/v1/members").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private List<String> names(String q) throws Exception {
        String body = mockMvc.perform(get("/api/v1/members").param("q", q).param("size", "50")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> names = new ArrayList<>();
        readJson(body).get("content").forEach(m -> names.add(m.get("fullName").asString()));
        return names;
    }

    private List<String> column(String sort, String direction, String field) throws Exception {
        String body = mockMvc.perform(get("/api/v1/members").param("sort", sort).param("direction", direction)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> values = new ArrayList<>();
        readJson(body).get("content").forEach(m ->
                values.add(m.get(field) == null || m.get(field).isNull() ? null : m.get(field).asString()));
        return values;
    }
}
