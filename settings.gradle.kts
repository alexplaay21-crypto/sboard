pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("tools") { from(files("third_party/florisboard/gradle/tools.versions.toml")) }
    }
}
rootProject.name = "sBoard"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app", ":keyboard:floris")
include(":lib:android", ":lib:color", ":lib:compose", ":lib:kotlin", ":lib:snygg")
project(":lib:android").projectDir = file("third_party/florisboard/lib/android")
project(":lib:color").projectDir = file("third_party/florisboard/lib/color")
project(":lib:compose").projectDir = file("third_party/florisboard/lib/compose")
project(":lib:kotlin").projectDir = file("third_party/florisboard/lib/kotlin")
project(":lib:snygg").projectDir = file("third_party/florisboard/lib/snygg")
