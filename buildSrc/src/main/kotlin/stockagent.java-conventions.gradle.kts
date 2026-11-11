// Shared by every module: Java 25 toolchain, UTF-8, Spotless (google-java-format), JUnit Platform.
plugins {
    java
    id("com.diffplug.spotless")
}

group = "info.saagar.stockagent"
version = "0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        endWithNewline()
    }
}

// Format before compiling locally; in CI only verify so unformatted code fails the build.
tasks.named("compileJava") {
    dependsOn(tasks.named(if (System.getenv("CI") != null) "spotlessCheck" else "spotlessApply"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
