package com.bankingax.core.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuthUserRepository extends JpaRepository<AuthUserEntity, Long> {
    Optional<AuthUserEntity> findByLoginId(String loginId);
}
