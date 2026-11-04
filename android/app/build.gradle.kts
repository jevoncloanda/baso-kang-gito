import java.util.Properties
import java.net.URI
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
val productionPin = JsonSlurper().parse(rootProject.file("production-signing.json")) as Map<*, *>
val updateCertificate = productionPin["certificateSha256"] as String
require(productionPin["packageId"] == "id.basokanggito.app" && updateCertificate.matches(Regex("[a-f0-9]{64}"))) { "Invalid public production certificate pin" }

android {
    namespace = "id.basokanggito.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.basokanggito.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2"
        buildConfigField("String", "UPDATE_CERTIFICATE_SHA256", "\"$updateCertificate\"")
        testInstrumentationRunner = "id.basokanggito.app.NotebookTestRunner"
    }

    buildFeatures { compose = true; buildConfig = true; resValues = true }

    signingConfigs {
        create("production") {
            storeFile = file(localConfig.getProperty("bookkeeping.signingStoreFile"))
            storePassword = localConfig.getProperty("bookkeeping.signingStorePassword")
            keyAlias = localConfig.getProperty("bookkeeping.signingKeyAlias")
            keyPassword = localConfig.getProperty("bookkeeping.signingKeyPassword")
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "application_label", "Baso Kang Gito DEV")
            buildConfigField("String", "SYNC_URL", "\"http://127.0.0.1:3000\"")
            // Legacy value is validation-helper control only; never authenticates financial routes.
            buildConfigField("String", "SYNC_TOKEN", "\"\"")
        }
        getByName("release") {
            resValue("string", "application_label", "Baso Kang Gito")
            signingConfig = signingConfigs.getByName("production")
            val apiUrl = localConfig.getProperty("bookkeeping.apiUrl", "")
            val endpoint = runCatching { URI(apiUrl) }.getOrNull()
            require(apiUrl.isEmpty() || (endpoint?.scheme == "https" && !endpoint.host.isNullOrBlank() &&
                endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null && endpoint.rawPath in listOf("", "/"))) { "Release API must be an HTTPS origin without credentials, query or fragment" }
            buildConfigField("String", "SYNC_URL", "\"${apiUrl.trimEnd('/')}\"")
            buildConfigField("String", "SYNC_TOKEN", "\"\"")
        }
        create("validation") {
            initWith(getByName("debug"))
            // Separate from older validation APKs signed on another development host.
            applicationIdSuffix = ".access.validation"
            resValue("string", "application_label", "Baso Kang Gito")
            matchingFallbacks += "debug"
            buildConfigField("String", "SYNC_URL", "\"http://127.0.0.1:3001\"")
            val controlToken = localConfig.getProperty("bookkeeping.devToken", "")
            require(controlToken.isEmpty() || controlToken.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Invalid isolated test-control token" }
            buildConfigField("String", "SYNC_TOKEN", "\"$controlToken\"")
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
    // Cryptographically verify APK signatures before handing an update to Android.
    implementation("com.android.tools.build:apksig:9.0.1")
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

tasks.withType<Test>().configureEach {
    providers.gradleProperty("apkFixture").orNull?.let { systemProperty("bkg.apkFixture", it) }
}
