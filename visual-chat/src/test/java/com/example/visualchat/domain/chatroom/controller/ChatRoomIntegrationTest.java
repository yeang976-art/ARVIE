package com.example.visualchat.domain.chatroom.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.auth.port.TokenIssuer;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.chatroom.repository.ChatRoomRepository;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.reve.enums.Mbti;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import com.example.visualchat.domain.user.repository.UserReveRepository;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 실제 JWT와 저장소로 채팅방 열기·설정 조회·소유권 검사를 검증 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-room-issue34;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
class ChatRoomIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private UserReveRepository assignments;
    @Autowired private ReveRepository reves;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private TokenIssuer tokens;
    @Autowired private JwtEncoder encoder;
    private User owner;
    private User other;
    private Reve reve;
    private Reve otherReve;
    private Reve unassignedReve;
    private UserReve assignment;
    private UserReve otherAssignment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    @BeforeEach
    void prepareAssignments() {
        // [CAUTION] 이 클래스 전용 메모리 DB만 정리하며 개발 DB에는 접근하지 않음
        rooms.deleteAll();
        assignments.deleteAll();
        users.deleteAll();
        reves.deleteAll();
        owner = users.saveAndFlush(User.of("owner", "owner@example.com", AuthTestFixtures.dummyPasswordHash()));
        other = users.saveAndFlush(User.of("other", "other@example.com", AuthTestFixtures.dummyPasswordHash()));
        reve = reves.saveAndFlush(Reve.of("OWN", "루미", Mbti.INFP, "소개", "서버 프롬프트", BigDecimal.ONE));
        otherReve = reves.saveAndFlush(Reve.of("OTHER", "다른 레브", Mbti.INTJ, "소개", "다른 프롬프트", BigDecimal.ONE));
        unassignedReve = reves.saveAndFlush(Reve.of("UNASSIGNED", "미배정 레브", Mbti.ENFP, "소개", "프롬프트", BigDecimal.ONE));
        assignment = assignments.saveAndFlush(UserReve.of(owner, reve));
        otherAssignment = assignments.saveAndFlush(UserReve.of(other, otherReve));
    }

    @Test
    @DisplayName("최초 열기는 기본 보관 기간 90일인 본인 채팅방을 커밋하고 공개 필드만 반환한다")
    void opensFirstRoomWithDefaultSettings() throws Exception {
        Map<?, ?> data = data(open().andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.retentionDays").value(90))
                .andReturn().getResponse().getContentAsString());

        assertThat(data.size()).isEqualTo(2);
        long roomId = ((Number) data.get("roomId")).longValue();
        assertThat(rooms.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select user_id from chat_rooms where id = ?", Long.class, roomId)).isEqualTo(owner.getId());
        assertThat(jdbc.queryForObject("select user_reve_id from chat_rooms where id = ?", Long.class, roomId)).isEqualTo(assignment.getId());
    }

    @Test
    @DisplayName("반복 요청과 재접속에서도 같은 방과 변경된 보관 설정을 유지한다")
    void preservesExistingRoomAndSettings() throws Exception {
        long roomId = roomId(open().andExpect(status().isOk()));
        ChatRoom saved = rooms.findById(roomId).orElseThrow();
        saved.modifyRetentionDays((short) 30);
        rooms.saveAndFlush(saved);

        for (int request = 0; request < 3; request++) {
            mockMvc.perform(post("/api/reves/{id}/chat-rooms", reve.getId())
                            .session(new MockHttpSession())
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.roomId").value(roomId))
                    .andExpect(jsonPath("$.data.retentionDays").value(30));
        }
        assertThat(rooms.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("본인 방의 현재 보관 설정을 조회한다")
    void readsOwnSettings() throws Exception {
        ChatRoom room = ChatRoom.of(assignment);
        room.modifyRetentionDays((short) 30);
        rooms.saveAndFlush(room);

        mockMvc.perform(get("/api/chat-rooms/{id}/settings", room.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(room.getId()))
                .andExpect(jsonPath("$.data.retentionDays").value(30));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("미배정 레브와 타인에게만 배정된 레브로는 방을 만들 수 없다")
    void rejectsUnownedReve(boolean assignedToAnotherMember) throws Exception {
        Long reveId = assignedToAnotherMember ? otherReve.getId() : unassignedReve.getId();
        assertError(mockMvc.perform(post("/api/reves/{id}/chat-rooms", reveId)
                        .header(HttpHeaders.AUTHORIZATION, bearer())), ErrorCode.USER_REVE_NOT_FOUND);
        assertThat(rooms.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("타인 방과 존재하지 않는 방의 설정은 동일한 404로 처리한다")
    void rejectsForeignOrMissingRoom(boolean existingForeignRoom) throws Exception {
        Long roomId = existingForeignRoom
                ? rooms.saveAndFlush(ChatRoom.of(otherAssignment)).getId() : Long.MAX_VALUE;
        assertError(mockMvc.perform(get("/api/chat-rooms/{id}/settings", roomId)
                        .header(HttpHeaders.AUTHORIZATION, bearer())), ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "settings"})
    @DisplayName("채팅방 열기와 설정 조회는 인증 토큰 없이 접근할 수 없다")
    void rejectsAnonymousRequests(String endpoint) throws Exception {
        assertError(mockMvc.perform(request(endpoint, "1")), ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @ParameterizedTest
    @CsvSource({"open,not-a-number", "settings,0", "open,-1", "settings,9223372036854775808"})
    @DisplayName("서명된 토큰도 회원 식별값이 양수 Long이 아니면 401로 거절한다")
    void rejectsInvalidSubject(String endpoint, String subject) throws Exception {
        String token = JwtTestFixtures.sign(encoder, JwtTestFixtures.claims().subject(subject).build());
        assertError(mockMvc.perform(request(endpoint, reve.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), ErrorCode.INVALID_TOKEN);
    }

    @ParameterizedTest
    @CsvSource({"open,0", "open,-1", "open,not-an-id", "settings,0", "settings,-1", "settings,not-an-id"})
    @DisplayName("경로의 레브·채팅방 ID가 잘못되면 저장 없이 400을 반환한다")
    void rejectsInvalidResourceIdentifier(String endpoint, String id) throws Exception {
        String message = "not-an-id".equals(id)
                ? "요청 파라미터의 형식이 올바르지 않습니다." : ErrorCode.INVALID_INPUT.getMessage();
        assertError(mockMvc.perform(request(endpoint, id).header(HttpHeaders.AUTHORIZATION, bearer())),
                ErrorCode.INVALID_INPUT, message);
        assertThat(rooms.count()).isZero();
    }

    @Test
    @DisplayName("요청에 타인 회원 ID를 넣어도 토큰의 회원을 소유자로 사용한다")
    void ignoresClientSuppliedOwner() throws Exception {
        mockMvc.perform(post("/api/reves/{id}/chat-rooms", reve.getId())
                        .param("userId", other.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select user_id from chat_rooms", Long.class)).isEqualTo(owner.getId());
    }

    private ResultActions open() throws Exception {
        return mockMvc.perform(post("/api/reves/{id}/chat-rooms", reve.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer()));
    }

    private MockHttpServletRequestBuilder request(String endpoint, String id) {
        return "open".equals(endpoint) ? post("/api/reves/{id}/chat-rooms", id)
                : get("/api/chat-rooms/{id}/settings", id);
    }

    private String bearer() {
        return "Bearer " + tokens.issue(owner.getId()).token();
    }

    private long roomId(ResultActions result) throws Exception {
        return ((Number) data(result.andReturn().getResponse().getContentAsString()).get("roomId")).longValue();
    }

    private Map<?, ?> data(String json) {
        Map<?, ?> response = mapper.readValue(json, Map.class);
        return (Map<?, ?>) response.get("data");
    }

    private void assertError(ResultActions result, ErrorCode code) throws Exception {
        assertError(result, code, code.getMessage());
    }

    private void assertError(ResultActions result, ErrorCode code, String message) throws Exception {
        result.andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
