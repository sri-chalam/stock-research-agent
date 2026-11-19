package info.saagar.stockagent.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import info.saagar.stockagent.common.testing.TestJwts;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/// The Docker Compose healthcheck needs `/actuator/health`; no other actuator endpoint may be
/// exposed, because `/actuator/env` would reveal the Finnhub key. Runs through the security
/// filters, because the healthcheck sends no token.
@SpringBootTest(properties = "FINNHUB_API_KEY=synthetic-finnhub-key")
class ActuatorEndpointsTest {

  @DynamicPropertySource
  static void trustTheTestIssuer(DynamicPropertyRegistry propertyRegistry) {
    TestJwts.registerResourceServerProperties(propertyRegistry);
  }

  @Autowired private WebApplicationContext webApplicationContext;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(springSecurityFilterChain)
            .build();
  }

  @Test
  void healthReportsUpWithoutAToken() throws Exception {
    MvcResult healthResult = mockMvc.perform(get("/actuator/health")).andReturn();

    assertThat(healthResult.getResponse().getStatus()).isEqualTo(200);
    assertThat(healthResult.getResponse().getContentAsString()).contains("\"status\":\"UP\"");
  }

  @Test
  void envEndpointIsNotExposed() throws Exception {
    MvcResult envResult =
        mockMvc
            .perform(
                get("/actuator/env")
                    .header(
                        "Authorization",
                        "Bearer " + TestJwts.token("user_actuator_test", "marketdata.read")))
            .andReturn();

    assertThat(envResult.getResponse().getStatus()).isEqualTo(404);
  }
}
