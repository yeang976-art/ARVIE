package com.example.visualchat.domain.user.repository;

import com.example.visualchat.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** [ROLE] 회원 엔티티의 영속화와 이메일 기반 조회를 제공 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
}