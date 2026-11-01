package com.bankingax.core.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user")
class AuthUserEntity {
    @Id
    long id;

    @Column(name = "login_id", nullable = false, unique = true)
    String loginId;

    @Column(name = "password_hash", nullable = false)
    String passwordHash;

    @Column(name = "role", nullable = false)
    String role;

    @Column(name = "name", nullable = false)
    String name;

    @Column(name = "is_active", nullable = false)
    boolean active;
}
