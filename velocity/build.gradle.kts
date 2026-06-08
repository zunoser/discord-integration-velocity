dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    implementation("net.dv8tion:JDA:6.4.2")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.jar {
    archiveBaseName.set("discord-integration-velocity")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
