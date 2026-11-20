package info.saagar.stockagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/// OAuth2 resource server: the issuer, JWK set and authorities claim come only from the
/// `spring.security.oauth2.resourceserver.jwt.*` properties (SEC-2). `/api/**` needs the
/// `agent.chat` permission; the Docker Compose healthcheck reaches `/actuator/health` without a
/// token. The chat page (`/`, served from `static/index.html`) and its `/ui-config` are public: the
/// page signs the user in and then sends the token on every `/api/**` call.
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
                    .requestMatchers("/actuator/health", "/", "/index.html", "/ui-config")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .hasAuthority("SCOPE_agent.chat")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .build();
  }
}
