plugins {
    id("stockagent.spring-app")
}

dependencies {
    implementation(project(":common"))
    implementation(platform(libs.langchain4j.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.community.mcp.server)

    testImplementation(libs.spring.boot.starter.test)
}
