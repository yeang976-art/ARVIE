package com.example.visualchat.domain.user.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.auth.port.TokenIssuer;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.reve.enums.Mbti;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import com.example.visualchat.domain.reve.service.ReveService;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import com.example.visualchat.domain.user.repository.UserReveRepository;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 실제 JWT로 레브 배정 조회의 공개 필드·소유권·반복 조회 불변성을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-reve-issue33;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
class UserReveIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private UserReveRepository assignments;
    @Autowired private ReveRepository reves;
    @Autowired private TokenIssuer tokenIssuer;
    @Autowired private JwtEncoder jwtEncoder;
    @MockitoSpyBean private ReveService reveService;

    private User owner;
    private User otherMember;
    private Reve assignedReve;
    private UserReve assignment;
    private UserReve otherAssignment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    @BeforeEach
    void prepareAssignments() {
        // [CAUTION] 이 클래스의 전용 메모리 DB만 정리하며 개발 DB에는 접근하지 않음
        assignments.deleteAll();
        users.deleteAll();
        reves.deleteAll();
        owner = users.saveAndFlush(User.of("owner", "owner@example.com", AuthTestFixtures.dummyPasswordHash()));
        otherMember = users.saveAndFlush(User.of("other", "other@example.com", AuthTestFixtures.dummyPasswordHash()));
        assignedReve = reves.saveAndFlush(Reve.of("REVE_OWN", "루미", Mbti.INFP, "내 레브 소개", "공개하지 않을 서버 프롬프트", BigDecimal.ONE));
        Reve otherReve = reves.saveAndFlush(Reve.of("REVE_OTHER", "다른 레브", Mbti.INTJ, "다른 회원의 레브 소개", "다른 서버 설정", BigDecimal.TEN));
        assignment = assignments.saveAndFlush(UserReve.of(owner, assignedReve));
        otherAssignment = assignments.saveAndFlush(UserReve.of(otherMember, otherReve));
        assignment = assignments.findById(assignment.getId()).orElseThrow();
    }

    @Test
    @DisplayName("본인에게 저장된 배정과 MBTI를 반환하고 서버용 필드는 노출하지 않는다")
    void returnsOnlyPublicAssignmentFields() throws Exception {
        var response = request(tokenIssuer.issue(owner.getId()).token())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userReveId").value(assignment.getId()))
                .andExpect(jsonPath("$.data.reveId").value(assignedReve.getId()))
                .andExpect(jsonPath("$.data.name").value("루미"))
                .andExpect(jsonPath("$.data.mbti").value("INFP"))
                .andExpect(jsonPath("$.data.description").value("내 레브 소개"))
                .andReturn().getResponse();

        Map<?, ?> data = data(response.getContentAsString());
        assertThat(data.size()).isEqualTo(6);
        assertThat(Instant.parse((String) data.get("assignedAt"))).isEqualTo(assignment.getAssignedAt());
        assertThat(response.getContentAsString()).doesNotContain(
                "systemPrompt", "system_prompt", "trialWeight", "trialEnabled", "passwordHash",
                "공개하지 않을 서버 프롬프트", owner.getPasswordHash());
    }

    @Test
    @DisplayName("재접속과 다른 기기 요청에서도 같은 배정을 반환하고 재추첨하거나 새 배정을 만들지 않는다")
    void preservesAssignmentAcrossIndependentRequests() throws Exception {
        String token = tokenIssuer.issue(owner.getId()).token();
        Map<?, ?> expected = null;
        for (String device : new String[]{"phone", "tablet", "reconnected-phone"}) {
            var response = mockMvc.perform(get("/api/users/me/reves")
                            .session(new MockHttpSession())
                            .header(HttpHeaders.USER_AGENT, device)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse();
            Map<?, ?> current = data(response.getContentAsString());
            if (expected == null) {
                expected = current;
            } else {
                assertThat(current).isEqualTo(expected);
            }
        }
        assertThat(assignments.count()).isEqualTo(2);
        assertThat(reves.count()).isEqualTo(2);
        verify(reveService, never()).selectTrialReve();
    }

    @Test
    @DisplayName("다른 회원과 배정 ID를 입력해도 토큰에 해당하는 본인 배정만 반환한다")
    void ignoresOtherMembersIdentifiers() throws Exception {
        mockMvc.perform(get("/api/users/me/reves")
                        .param("userId", otherMember.getId().toString())
                        .param("userReveId", otherAssignment.getId().toString())
                        .param("reveId", otherAssignment.getReve().getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(owner.getId()).token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userReveId").value(assignment.getId()))
                .andExpect(jsonPath("$.data.reveId").value(assignedReve.getId()));
    }

    @Test
    @DisplayName("본인 배정이 없으면 다른 회원 ID를 전달해도 배정 없음 404를 반환한다")
    void rejectsMissingAssignmentWithoutReturningAnotherMembersData() throws Exception {
        assignments.deleteById(assignment.getId());
        assertError(mockMvc.perform(get("/api/users/me/reves")
                        .param("userId", otherMember.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(owner.getId()).token())),
                ErrorCode.USER_REVE_NOT_FOUND);
        assertThat(assignments.count()).isEqualTo(1);
        verify(reveService, never()).selectTrialReve();
    }

    @Test
    @DisplayName("인증 토큰 없이 회원 ID만 입력하면 배정 조회를 허용하지 않는다")
    void rejectsAnonymousRequest() throws Exception {
        assertError(mockMvc.perform(get("/api/users/me/reves").param("userId", owner.getId().toString())),
                ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-member-id", "0"})
    @DisplayName("서명된 JWT라도 회원 식별값이 잘못되면 401을 반환한다")
    void rejectsInvalidSubject(String subject) throws Exception {
        String token = JwtTestFixtures.sign(jwtEncoder, JwtTestFixtures.claims().subject(subject).build());
        assertError(request(token), ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("MBTI가 아직 지정되지 않은 배정 레브도 정상 조회할 수 있다")
    void returnsReveWithUnspecifiedMbti() throws Exception {
        assignments.deleteById(assignment.getId());
        Reve unspecified = reves.saveAndFlush(Reve.of("REVE_UNSPECIFIED", "미지정 레브", null, "소개", "서버 설정", BigDecimal.ONE));
        UserReve replacement = assignments.saveAndFlush(UserReve.of(owner, unspecified));

        var response = request(tokenIssuer.issue(owner.getId()).token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userReveId").value(replacement.getId()))
                .andExpect(jsonPath("$.data.name").value("미지정 레브"))
                .andReturn().getResponse();
        assertThat(data(response.getContentAsString()).get("mbti")).isNull();
    }

    @Test
    @DisplayName("체험 추첨 대상에서 제외되어도 이미 배정된 레브는 조회할 수 있다")
    void stillReturnsAssignedReveAfterTrialExclusion() throws Exception {
        assignedReve.exceptTrial();
        reves.saveAndFlush(assignedReve);

        request(tokenIssuer.issue(owner.getId()).token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reveId").value(assignedReve.getId()))
                .andExpect(jsonPath("$.data.mbti").value("INFP"));
    }

    private ResultActions request(String token) throws Exception {
        return mockMvc.perform(get("/api/users/me/reves").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Map<?, ?> data(String json) {
        Map<?, ?> body = objectMapper.readValue(json, Map.class);
        return (Map<?, ?>) body.get("data");
    }

    private void assertError(ResultActions result, ErrorCode errorCode) throws Exception {
        result.andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
