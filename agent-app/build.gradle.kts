plugins {
    id("stockagent.spring-app")
}

dependencies {
    implementation(project(":common"))
    implementation(platform(libs.langchain4j.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.bundles.langchain4j)

    testImplementation(libs.spring.boot.starter.test)
}
