plugins {
    id("stockagent.spring-app")
}

dependencies {
    implementation(project(":common"))

    implementation(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test)
}
