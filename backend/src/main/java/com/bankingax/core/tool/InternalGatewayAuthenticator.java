package com.bankingax.core.tool;

import com.bankingax.core.api.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InternalGatewayAuthenticator {
    private final String expectedToken;
    public InternalGatewayAuthenticator(@Value("${app.tool-gateway-token:}") String expectedToken) {
        this.expectedToken = expectedToken;
    }
    public void verify(String suppliedToken) {
        if (expectedToken.isBlank() || !expectedToken.equals(suppliedToken))
            throw new DomainException("FORBIDDEN", "Trusted Tool Gateway authentication failed.");
    }
}
