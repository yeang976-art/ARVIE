package com.example.visualchat.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** [ROLE] 엔티티의 생성·수정 시각을 기록하는 JPA Auditing을 활성화 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

}
