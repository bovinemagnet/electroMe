plugins {
    id("io.quarkus")
}

val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusPlatformVersion"))

    implementation(project(":core"))
    implementation(project(":ingest"))

    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-rest-qute")
    implementation("io.quarkus:quarkus-arc")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
}
