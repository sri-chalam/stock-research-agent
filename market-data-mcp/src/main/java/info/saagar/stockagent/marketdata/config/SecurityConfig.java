package info.saagar.stockagent.marketdata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/// OAuth2 resource server: the issuer, JWK set and authorities claim come only from the
/// `spring.security.oauth2.resourceserver.jwt.*` properties (SEC-2).
///
/// A bearer token, when sent, is validated on every request, so a bad one gets 401 whatever the
/// JSON-RPC method. `POST /mcp` is otherwise open at this layer: the agent app's MCP client sends
/// `initialize` and its `ping` health check outside any chat request, with no user token.
/// `McpController` requires the token and `marketdata.read` for `tools/list` and `tools/call`.
@Configuration
public class SecurityConfig {

  /// Builds the filter chain.
  ///
  /// @param httpSecurity Spring Security's builder
  /// @return the filter chain
  /// @throws Exception if the chain cannot be built
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
    return httpSecurity
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/mcp")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .build();
  }
}
