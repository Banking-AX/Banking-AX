package com.bankingax.core.auth;

enum AuthRole {
    CUSTOMER("CUSTOMER", "CUSTOMER"),
    EMPLOYEE("STAFF", "EMPLOYEE"),
    ADMIN("ADMIN", "ADMIN");

    private final String databaseValue;
    private final String authorityValue;

    AuthRole(String databaseValue, String authorityValue) {
        this.databaseValue = databaseValue;
        this.authorityValue = authorityValue;
    }

    static AuthRole fromDatabase(String value) {
        for (AuthRole role : values()) {
            if (role.databaseValue.equals(value)) return role;
        }
        throw new AuthFailureException();
    }

    String apiValue() {
        return databaseValue;
    }

    String authorityValue() {
        return authorityValue;
    }
}
