import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("stockagent.java-conventions")
    `java-library`
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(libs.spring.boot.starter.web)
}
