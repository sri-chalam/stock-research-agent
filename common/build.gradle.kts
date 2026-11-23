import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("stockagent.java-conventions")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(libs.spring.boot.starter.web)
    // LoggingContextFilter reads the current span's trace ID. Without the Java agent (bootRun, tests)
    // the API is a no-op and there is no valid span (ADR 0013).
    implementation(libs.opentelemetry.api)

    testImplementation(libs.spring.boot.starter.test)

    // TestJwts: signed test tokens and a loopback JWK set for the resource-server tests.
    testFixturesImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testFixturesImplementation(libs.spring.boot.starter.oauth2.resource.server)
    testFixturesImplementation(libs.spring.test)
}
