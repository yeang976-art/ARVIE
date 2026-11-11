package com.example.visualchat.domain.user.util;

import java.util.Locale;

/** [ROLE] 회원 이메일의 도메인 대소문자 표기를 통일 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalizeDomain(String email) {
        // [NOTE] 형식·필수값 검증은 기존 요청 검증과 DB 제약에서 담당
        if (email == null) {
            return null;
        }

        int separator = email.lastIndexOf('@');

        if (separator < 0) {
            return email;
        }

        // [WHY] 로컬 부분은 보존하고 대소문자를 구분하지 않는 도메인만 정규화
        return email.substring(0, separator + 1)
                + email.substring(separator + 1).toLowerCase(Locale.ROOT);
    }
}