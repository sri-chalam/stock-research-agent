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
    implementation(libs.langchain4j.pgvector)
    implementation(libs.hikaricp)
    implementation(libs.langchain4j.embeddings.bge.small.en.v15.q)
    implementation(libs.langchain4j.document.transformer.jsoup)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":common")))
}
