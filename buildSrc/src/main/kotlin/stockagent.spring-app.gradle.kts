import org.springframework.boot.gradle.plugin.SpringBootPlugin

// A runnable Spring Boot application: the Java conventions plus Spring Boot and its BOM.
// Applied by agent-app and market-data-mcp (not common, which is a plain library).
plugins {
    id("stockagent.java-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

// The OpenTelemetry Java agent, for the Docker images only (ADR 0013): bootRun, the build and the
// tests run without it.
val otelJavaagent by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    "implementation"(platform(SpringBootPlugin.BOM_COORDINATES))
    otelJavaagent(libs.findLibrary("opentelemetry-javaagent").get())
}

// Run by each Dockerfile's build stage; the image's entrypoint passes the jar to -javaagent.
tasks.register<Copy>("copyOtelJavaagent") {
    description = "Copies the OpenTelemetry Java agent to build/otel/opentelemetry-javaagent.jar."
    from(otelJavaagent)
    into(layout.buildDirectory.dir("otel"))
    rename { "opentelemetry-javaagent.jar" }
}
