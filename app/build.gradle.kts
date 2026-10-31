import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") }

val androidSdk = providers.environmentVariable("ANDROID_HOME").orElse("/opt/android-sdk").get()
val compileApi = providers.gradleProperty("compileApi").orElse("35").get()
val androidJar = files("$androidSdk/platforms/android-$compileApi/android.jar")
// Recursos ligados contra API 33 (o aapt v1 não lê o resources.arsc da API 35).
val linkJar = "$androidSdk/platforms/android-33/android.jar"

val genR = layout.buildDirectory.dir("generated/r")
val resApk = layout.buildDirectory.file("res/resources.ap_")
val testCfgDir = layout.buildDirectory.dir("generated/testcfg")

val aaptResources by tasks.registering(Exec::class) {
    description = "Gera R.java e resources.ap_ com aapt"
    inputs.dir("src/main/res"); inputs.file("src/main/AndroidManifest.xml")
    outputs.dir(genR); outputs.file(resApk)
    doFirst { genR.get().asFile.mkdirs(); resApk.get().asFile.parentFile.mkdirs() }
    commandLine("aapt", "package", "-f", "-m", "--auto-add-overlay",
        "-J", genR.get().asFile.absolutePath, "-M", "src/main/AndroidManifest.xml", "-S", "src/main/res",
        "-I", linkJar, "-F", resApk.get().asFile.absolutePath)
}

val robolectricConfig by tasks.registering {
    val out = testCfgDir.get().file("com/android/tools/test_config.properties").asFile
    outputs.file(out)
    doLast {
        out.parentFile.mkdirs()
        out.writeText(
            "android_resource_apk=${resApk.get().asFile.absolutePath}\n" +
            "android_merged_manifest=${file("src/main/AndroidManifest.xml").absolutePath}\n" +
            "android_merged_assets=${file("src/main/assets").absolutePath}\n" +
            "android_custom_package=com.matilha.app\n"
        )
    }
}

sourceSets {
    main {
        kotlin.srcDirs("src/main/java")
        java.srcDirs(genR)
    }
    test {
        resources.srcDir(testCfgDir)
    }
    // Shim de androidx.test (só para testes; ver src/testShim/java/androidx/test/README.txt).
    create("testShim") { java.srcDirs("src/testShim/java") }
}

tasks.named("compileKotlin") { dependsOn(aaptResources) }
tasks.named("compileJava") { dependsOn(aaptResources) }
tasks.named("processTestResources") { dependsOn(robolectricConfig, aaptResources) }

java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
kotlin {
    // A stdlib 2.x é compilada com lambdas invokedynamic (quebram no ART via dx). A 1.9.24 não.
    coreLibrariesVersion = "1.9.24"
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        jvmTarget.set(JvmTarget.JVM_1_8)
        // O ART (Android) não tem LambdaMetafactory e o dx não "desaçucara" invokedynamic:
        // lambdas e conversões SAM precisam virar classes de verdade.
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline")
    }
}
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-options") }

// Runtime Android do Robolectric (android-all-instrumented) fica em build/robolectric-deps e o
// Robolectric roda offline. Baixe com: ./fetch-robolectric.sh

dependencies {
    "testShimCompileOnly"(androidJar)
    testImplementation(sourceSets["testShim"].output)
    compileOnly(androidJar)
    testCompileOnly(androidJar)
    testRuntimeOnly(androidJar)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1") { exclude(group = "androidx.test"); exclude(group = "androidx.test.espresso") }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-deps").get().asFile.absolutePath)
    systemProperty("matilha.screens", rootProject.layout.projectDirectory.dir("screens").asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Copy>("collectRuntimeJars") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("runtime-jars"))
}

