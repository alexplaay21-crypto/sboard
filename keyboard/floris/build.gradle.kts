import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.agp.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutlibraries)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.set(listOf(
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-Xjvm-default=all-compatibility",
            "-Xwhen-guards",
        ))
    }
}

configure<LibraryExtension> {
    namespace = "dev.patrickgold.florisboard"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // FlorisBoard normally declares these in its app build types. The
        // vendored runtime is a library, so provide sBoard-owned equivalents.
        resValue("string", "floris_app_name", "sBoard Keyboard")
        resValue("mipmap", "floris_app_icon", "@mipmap/ic_app_icon_stable")
        resValue("mipmap", "floris_app_icon_round", "@mipmap/ic_app_icon_stable_round")
        buildConfigField("String", "APPLICATION_ID", "\"com.sboard.app\"")
        buildConfigField("int", "VERSION_CODE", "1")
        buildConfigField("String", "VERSION_NAME", "\"0.1.0\"")
        buildConfigField("String", "BUILD_COMMIT_HASH", "\"2e82060251897226c0739b9f52d1d051b02305fb\"")
        buildConfigField("String", "FLADDONS_API_VERSION", "\"v0.5\"")
        buildConfigField("String", "FLADDONS_STORE_URL", "\"beta.addons.florisboard.org\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.named("main") {
        manifest.srcFile("src/main/AndroidManifest.xml")
        java.srcDir("../../third_party/florisboard/app/src/main/kotlin")
        res.srcDir("../../third_party/florisboard/app/src/main/res")
        assets.srcDir("../../third_party/florisboard/app/src/main/assets")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

aboutLibraries {
    collect { configPath = file("../../third_party/florisboard/app/src/main/config") }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.views)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.cache4k)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mikepenz.aboutlibraries.core)
    implementation(libs.mikepenz.aboutlibraries.compose)
    implementation(libs.patrickgold.compose.tooltip)
    implementation(libs.patrickgold.jetpref.datastore.model)
    ksp(libs.patrickgold.jetpref.datastore.model.processor)
    implementation(libs.patrickgold.jetpref.datastore.ui)
    implementation(libs.patrickgold.jetpref.material.ui)
    implementation(project(":lib:android"))
    implementation(project(":lib:color"))
    implementation(project(":lib:compose"))
    implementation(project(":lib:kotlin"))
    implementation(project(":lib:snygg"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.params)
}
