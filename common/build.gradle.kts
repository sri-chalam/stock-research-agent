import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("stockagent.java-conventions")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(libs.spring.boot.starter.web)

    // TestJwts: signed test tokens and a loopback JWK set for the resource-server tests.
    testFixturesImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testFixturesImplementation(libs.spring.boot.starter.oauth2.resource.server)
    testFixturesImplementation(libs.spring.test)
}
