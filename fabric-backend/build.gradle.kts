import java.util.Properties
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("net.fabricmc.fabric-loom") version "1.17.2" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.17.2" apply false
}

val supportedVersions = Properties().apply {
    file("versions.properties").inputStream().use { load(it) }
}
val minecraftVersion = providers.gradleProperty("minecraftVersion").getOrElse("26.3")
require(supportedVersions.containsKey(minecraftVersion)) {
    "Unsupported minecraftVersion '$minecraftVersion'. Choose one of: ${supportedVersions.stringPropertyNames().sorted()}"
}
val obfuscated = minecraftVersion.startsWith("1.")
val legacyNetworking = minecraftVersion in listOf("1.20.1", "1.20.2", "1.20.4")
val javaVersion = if (legacyNetworking) 17 else if (obfuscated) 21 else 25
val compat = when {
    !obfuscated -> "unobfuscated"
    minecraftVersion == "1.21.11" -> "identifier"
    else -> "mojmap"
}

apply(plugin = if (obfuscated) "net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom")

base {
    archivesName.set("discord-integration-fabric-backend")
}
layout.buildDirectory.set(layout.projectDirectory.dir("build/$minecraftVersion"))

sourceSets.main {
    java.srcDir(if (legacyNetworking) "src/legacy/java" else "src/modern/java")
    if (!legacyNetworking) java.srcDir("src/$compat/java")
}

dependencies {
    add("minecraft", "com.mojang:minecraft:$minecraftVersion")
    if (obfuscated) {
        add("mappings", project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    }
    val modConfiguration = if (obfuscated) "modImplementation" else "implementation"
    add(modConfiguration, "net.fabricmc:fabric-loader:0.19.3")
    add(modConfiguration, "net.fabricmc.fabric-api:fabric-api:${supportedVersions.getProperty(minecraftVersion)}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
}

tasks.withType<Jar>().configureEach {
    archiveClassifier.set("mc$minecraftVersion")
}
if (obfuscated) {
    // Keep Loom's development jar separate from the installable, remapped jar.
    tasks.named<Jar>("jar") { archiveClassifier.set("mc$minecraftVersion-dev") }
    tasks.named<AbstractArchiveTask>("remapJar") { archiveClassifier.set("mc$minecraftVersion") }
}

tasks.processResources {
    val properties = mapOf(
        "version" to project.version,
        "minecraftVersion" to minecraftVersion,
        "fabricApiVersion" to supportedVersions.getProperty(minecraftVersion),
        "javaVersion" to javaVersion
    )
    inputs.properties(properties)
    filesMatching("fabric.mod.json") { expand(properties) }
}

val compatibilityTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("app.odango.discordvelocity.fabric.BackendCompatibilityTest")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    enableAssertions = true
}
// The assertion-based protocol check runs through compatibilityTest, without a test framework.
tasks.test { failOnNoDiscoveredTests.set(false) }
tasks.check { dependsOn(compatibilityTest) }
