plugins {
    application
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.github.nearkim.aicodewalkthrough.web.MainKt"
    applicationName = "ai-code-walkthrough"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

val webDirectory = rootProject.layout.projectDirectory.dir("web")
val installWebDependencies by tasks.registering(Exec::class) {
    workingDir(webDirectory)
    commandLine("npm", "ci")
    inputs.files(webDirectory.file("package.json"), webDirectory.file("package-lock.json"))
    outputs.dir(webDirectory.dir("node_modules"))
}

val buildWeb by tasks.registering(Exec::class) {
    dependsOn(installWebDependencies)
    workingDir(webDirectory)
    commandLine("npm", "run", "build")
    inputs.files(
        webDirectory.file("package.json"),
        webDirectory.file("package-lock.json"),
        webDirectory.file("tsconfig.json"),
        webDirectory.file("tsconfig.app.json"),
        webDirectory.file("tsconfig.node.json"),
        webDirectory.file("vite.config.ts"),
    )
    inputs.dir(webDirectory.dir("src"))
    inputs.file(webDirectory.file("index.html"))
    outputs.dir(webDirectory.dir("dist"))
}

tasks.processResources {
    dependsOn(buildWeb)
    from(webDirectory.dir("dist")) {
        into("web")
    }
}
