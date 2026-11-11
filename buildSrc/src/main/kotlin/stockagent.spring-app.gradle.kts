import org.springframework.boot.gradle.plugin.SpringBootPlugin

// A runnable Spring Boot application: the Java conventions plus Spring Boot and its BOM.
// Applied by agent-app and market-data-mcp (not common, which is a plain library).
plugins {
    id("stockagent.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    "implementation"(platform(SpringBootPlugin.BOM_COORDINATES))
}
