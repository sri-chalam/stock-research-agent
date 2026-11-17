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
    // Semantic cache: in-process embedding model and its own Postgres table.
    implementation(libs.langchain4j.embeddings.bge.small.en.v15.q)
    implementation(libs.spring.jdbc)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}
