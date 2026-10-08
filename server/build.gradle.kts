plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("ru.ryadom.server.ApplicationKt") }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.1.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.1.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.3")
}
