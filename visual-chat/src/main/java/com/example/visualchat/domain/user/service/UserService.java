package com.example.visualchat.domain.user.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.user.dto.UserMeResponse;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import com.example.visualchat.domain.user.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** [ROLE] 회원 조회·중복 검사·생성을 담당 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(EmailNormalizer.normalizeDomain(email));
    }

    // [TOPIC] 중복 검사 및 회원 생성
    @Transactional
    public User create(String nickname, String email, String passwordHash) {
        String normalizedEmail = EmailNormalizer.normalizeDomain(email);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ServiceException(ErrorCode.DUPLICATE_EMAIL);
        }

        if (userRepository.existsByNickname(nickname)) {
            throw new ServiceException(ErrorCode.DUPLICATE_NICKNAME);
        }

        // [NOTE] 가입 Command에서 해시 처리한 비밀번호를 전달받음
        User user = User.of(nickname, normalizedEmail, passwordHash);

        // [WHY] 사전 조회 이후의 동시 중복 가입은 DB 유일성 제약으로 최종 차단
        return userRepository.save(user);
    }

    // [TOPIC] 인증된 회원의 본인 정보 조회
    public UserMeResponse getMyInfo(Long userId) {
        // [CAUTION] 비밀번호 해시가 응답에 포함되지 않도록 전용 DTO로 변환
        return UserMeResponse.from(getById(userId));
    }

    // [TOPIC] 회원 ID로 내부 처리에 필요한 회원 조회
    public User getById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(ErrorCode.USER_NOT_FOUND));
    }
}
