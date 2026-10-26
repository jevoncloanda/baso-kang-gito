import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "id.basokanggito.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.basokanggito.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "id.basokanggito.app.NotebookTestRunner"
    }

    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "SYNC_URL", "\"http://127.0.0.1:3000\"")
            buildConfigField("String", "SYNC_TOKEN", "\"${localConfig.getProperty("bookkeeping.devToken", "")}\"")
        }
        getByName("release") {
            buildConfigField("String", "SYNC_URL", "\"\"")
            buildConfigField("String", "SYNC_TOKEN", "\"\"")
        }
        create("validation") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".validation"
            matchingFallbacks += "debug"
        }
    }
    // Connected-test cleanup must never uninstall the real notebook or remove its database.
    testBuildType = "validation"
    sourceSets.getByName("validation") {
        res.directories.add("src/debug/res")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    // Both languages must be available offline when changed from inside the app.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("validation")) { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("src/debug/java")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compose's transitive Espresso 3.5.0 uses an InputManager method absent on Android 17.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    add("validationImplementation", "androidx.compose.ui:ui-test-manifest")
}
