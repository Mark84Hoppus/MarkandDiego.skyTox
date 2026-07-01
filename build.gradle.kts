plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.googleServices) apply false
}

tasks.register("clean").configure {
    delete("build")
}
