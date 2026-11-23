plugins {
    id("stockagent.spring-app")
}

dependencies {
    implementation(project(":common"))
    implementation(platform(libs.langchain4j.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.community.mcp.server)
    // One span per tool call (ADR 0013); without the Java agent (bootRun, tests) @WithSpan does
    // nothing.
    implementation(libs.opentelemetry.instrumentation.annotations)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":common")))
}
