plugins {
    java
}

allprojects {
    group = "io.github.bovinemagnet.electrome"
    version = "0.0.0"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:5.11.3"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core:3.26.3")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:all")
    }
}
