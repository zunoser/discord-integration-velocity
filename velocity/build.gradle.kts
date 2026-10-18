dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    implementation("net.dv8tion:JDA:6.4.2")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    // JDA exposes this existing runtime dependency in its OptionMapping constructor.
    testImplementation("net.sf.trove4j:core:3.1.0")
}

val authenticationTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("app.odango.discordvelocity.AuthenticationTest")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    enableAssertions = true
}
tasks.test { failOnNoDiscoveredTests.set(false) }
tasks.check { dependsOn(authenticationTest) }

tasks.jar {
    archiveBaseName.set("discord-integration-velocity")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
