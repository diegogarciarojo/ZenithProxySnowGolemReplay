plugins { id("zenithproxy.plugin.dev") version "1.2.0" }
group = property("maven_group") as String
version = property("plugin_version") as String
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
zenithProxyPlugin {
    buildConstants { fields = mapOf("VERSION" to project.version.toString(), "MC_VERSION" to "1.21.4", "PLUGIN_ID" to "snow-golem-replay") }
    javaReleaseVersion = JavaLanguageVersion.of(21)
    runTaskMixinLauncher = true
}
repositories {
    maven("https://maven.2b2t.vc/releases")
    maven("https://maven.2b2t.vc/remote")
    mavenCentral()
}
dependencies {
    zenithProxy("com.zenith:ZenithProxy:3.7.0+1.21.4")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    workingDir = layout.buildDirectory.dir("test-runtime").get().asFile
    doFirst { workingDir.mkdirs() }
    maxHeapSize = "384m"
    systemProperty("golem.realTimeTest", providers.gradleProperty("realTimeTest").getOrElse("false"))
    systemProperty("golem.evidenceDir", layout.buildDirectory.dir("real-time-evidence").get().asFile.absolutePath)
}
