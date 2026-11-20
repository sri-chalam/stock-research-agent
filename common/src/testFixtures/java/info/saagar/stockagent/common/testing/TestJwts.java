package info.saagar.stockagent.common.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;

/// Signs test JWTs shaped like WorkOS access tokens (`iss`, `sub`, `exp`, `iat` and a `permissions`
/// array, no `aud`) and serves the matching JWK set on a loopback port, so a resource server under
/// test validates them through its real, property-configured decoder.
///
/// Register the properties with [#registerResourceServerProperties] from a `@DynamicPropertySource`
/// method.
public final class TestJwts {

  /// The issuer the resource servers under test trust.
  public static final String ISSUER = "https://auth.test.invalid/user_management/client_test";

  private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);
  private static final RSAKey SIGNING_KEY = generateSigningKey();
  private static final HttpServer JWK_SET_SERVER = startJwkSetServer();

  private TestJwts() {}

  /// Points the app's resource server at the test issuer and the loopback JWK set.
  ///
  /// @param propertyRegistry the registry passed to a `@DynamicPropertySource` method
  public static void registerResourceServerProperties(DynamicPropertyRegistry propertyRegistry) {
    propertyRegistry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    propertyRegistry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:" + JWK_SET_SERVER.getAddress().getPort() + "/jwks");
  }

  /// Returns a valid token from [#ISSUER].
  ///
  /// @param subject the user ID (`sub`)
  /// @param permissions the token's `permissions`, e.g. `agent.chat`
  /// @return the signed, compact JWT
  public static String token(String subject, String... permissions) {
    Instant issuedAt = Instant.now();
    return sign(ISSUER, subject, issuedAt, issuedAt.plus(TOKEN_LIFETIME), permissions);
  }

  /// Returns a token from [#ISSUER] that expired an hour ago, beyond any clock-skew allowance.
  ///
  /// @param subject the user ID (`sub`)
  /// @param permissions the token's `permissions`
  /// @return the signed, compact JWT
  public static String expiredToken(String subject, String... permissions) {
    Instant expiredAt = Instant.now().minus(Duration.ofHours(1));
    return sign(ISSUER, subject, expiredAt.minus(TOKEN_LIFETIME), expiredAt, permissions);
  }

  /// Returns an otherwise valid token whose `iss` is not [#ISSUER].
  ///
  /// @param issuer the untrusted issuer
  /// @param subject the user ID (`sub`)
  /// @param permissions the token's `permissions`
  /// @return the signed, compact JWT
  public static String tokenFromIssuer(String issuer, String subject, String... permissions) {
    Instant issuedAt = Instant.now();
    return sign(issuer, subject, issuedAt, issuedAt.plus(TOKEN_LIFETIME), permissions);
  }

  private static String sign(
      String issuer, String subject, Instant issuedAt, Instant expiresAt, String... permissions) {
    JWTClaimsSet tokenClaims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .claim("permissions", List.of(permissions))
            .build();
    SignedJWT signedToken =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(SIGNING_KEY.getKeyID())
                .build(),
            tokenClaims);
    try {
      signedToken.sign(new RSASSASigner(SIGNING_KEY));
    } catch (JOSEException signingFailure) {
      throw new IllegalStateException("Could not sign the test JWT", signingFailure);
    }
    return signedToken.serialize();
  }

  private static RSAKey generateSigningKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("test-key").generate();
    } catch (JOSEException keyGenerationFailure) {
      throw new IllegalStateException(
          "Could not generate the test signing key", keyGenerationFailure);
    }
  }

  private static HttpServer startJwkSetServer() {
    try {
      HttpServer jwkSetServer =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      byte[] jwkSetBytes =
          new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      jwkSetServer.createContext("/jwks", httpExchange -> serve(httpExchange, jwkSetBytes));
      jwkSetServer.start();
      return jwkSetServer;
    } catch (IOException startFailure) {
      throw new IllegalStateException("Could not start the test JWK set server", startFailure);
    }
  }

  private static void serve(HttpExchange httpExchange, byte[] jwkSetBytes) throws IOException {
    try (httpExchange) {
      httpExchange.getResponseHeaders().set("Content-Type", "application/json");
      httpExchange.sendResponseHeaders(200, jwkSetBytes.length);
      try (OutputStream responseBody = httpExchange.getResponseBody()) {
        responseBody.write(jwkSetBytes);
      }
    }
  }
}
