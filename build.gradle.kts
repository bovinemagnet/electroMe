plugins {
    java
    id("io.quarkus") version "3.38.2" apply false
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
            showStandardStreams = true
        }
        // Opt-in switches have to reach the forked test JVM, and have to be declared as
        // inputs or Gradle will serve a cached result from the previous setting.
        listOf("electrome.live", "electrome.usage.csv", "electrome.cache").forEach { key ->
            val value = providers.systemProperty(key)
            inputs.property(key, value.orElse(""))
            if (value.isPresent) {
                systemProperty(key, value.get())
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:all")
        // Qute's @CheckedTemplate binds template holes to method parameter names, which are
        // only present in the class file when javac is told to keep them.
        options.compilerArgs.add("-parameters")
    }
}

// ---------------------------------------------------------------------------
// Documentation
//
// Antora is a Node toolchain, so the build shells out to it. The npm install
// is a separate task with declared inputs and outputs, so it is skipped on
// every run after the first.
// ---------------------------------------------------------------------------

val npmInstall by tasks.registering(Exec::class) {
    description = "Installs the Antora toolchain."
    inputs.file("package.json")
    outputs.dir(layout.projectDirectory.dir("node_modules"))
    commandLine("npm", "install", "--no-audit", "--no-fund", "--silent")
}

tasks.register<Exec>("antora") {
    group = "documentation"
    description = "Builds the documentation site into build/docs/site."
    dependsOn(npmInstall)
    inputs.dir(layout.projectDirectory.dir("src/docs"))
    inputs.file("antora-playbook.yml")
    outputs.dir(layout.buildDirectory.dir("docs/site"))
    commandLine("npx", "antora", "antora-playbook.yml")
}
