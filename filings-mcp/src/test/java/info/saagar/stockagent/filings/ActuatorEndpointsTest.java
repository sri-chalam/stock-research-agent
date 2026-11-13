package info.saagar.stockagent.filings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/// The Docker Compose healthcheck needs `/actuator/health`; no other actuator endpoint may be
/// exposed, because `/actuator/env` would reveal the SEC User-Agent.
@SpringBootTest(properties = "SEC_USER_AGENT=synthetic-agent test@example.com")
class ActuatorEndpointsTest {

  @Autowired private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  void healthReportsUp() throws Exception {
    MvcResult healthResult = mockMvc.perform(get("/actuator/health")).andReturn();

    assertThat(healthResult.getResponse().getStatus()).isEqualTo(200);
    assertThat(healthResult.getResponse().getContentAsString()).contains("\"status\":\"UP\"");
  }

  @Test
  void envEndpointIsNotExposed() throws Exception {
    MvcResult envResult = mockMvc.perform(get("/actuator/env")).andReturn();

    assertThat(envResult.getResponse().getStatus()).isEqualTo(404);
  }
}
