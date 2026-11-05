package com.bankingax.core.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthSecurityConfiguration {
    @Bean
    SecurityFilterChain authSecurityFilterChain(HttpSecurity http, ObjectMapper json,
                                                InternalToolAuthenticationFilter internalToolFilter,
                                                AuthProperties properties)
            throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(properties.cookieSecure()).sameSite("Lax"));
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Stateless sessions make SessionManagementFilter treat every Bearer request as a new
                        // authentication; the default CSRF strategy would then delete XSRF-TOKEN on each API call
                        // and break the cookie-based refresh/logout below.
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .requireCsrfProtectionMatcher(new OrRequestMatcher(
                                new AntPathRequestMatcher("/api/v1/auth/refresh", "POST"),
                                new AntPathRequestMatcher("/api/v1/auth/logout", "POST"))))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/tools/**").hasRole("TOOL")
                        .requestMatchers("/api/v1/b02/**", "/api/v1/c01/**", "/api/v1/c02/**")
                        .hasRole("TOOL")
                        .requestMatchers("/api/v1/customer/**").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers("/api/v1/staff/**").hasAnyRole("EMPLOYEE", "ADMIN")
                        // Existing B/C controllers retain their own internal-token and ownership checks.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, exception) ->
                                SecurityErrorWriter.write(response, json, 401, "AUTH_REQUIRED",
                                        "A valid Bearer access token is required.")))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                        SecurityErrorWriter.write(response, json, 403, "AUTH_FORBIDDEN",
                                "The authenticated user does not have permission.")))
                .addFilterBefore(internalToolFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    InternalToolAuthenticationFilter internalToolAuthenticationFilter(
            @Value("${app.tool-gateway-token:}") String expectedToken) {
        return new InternalToolAuthenticationFilter(expectedToken);
    }

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(JwtKeyMaterial.from(properties.jwtSecret())));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        SecretKey key = JwtKeyMaterial.from(properties.jwtSecret());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) return new JwtAuthenticationToken(jwt);
            return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        };
    }
}
