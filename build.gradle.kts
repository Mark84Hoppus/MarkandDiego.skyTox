plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.googleServices) apply false
}

tasks.register("clean").configure {
    delete("build")
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.2.10")
                because("LiteRT-LM pulls Kotlin 2.4.0, but this project compiles with Kotlin 2.2.x.")
            }
        }
    }
}
