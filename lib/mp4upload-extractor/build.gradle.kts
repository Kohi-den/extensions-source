plugins {
    id("lib-android")
}

dependencies {
    implementation(libs.jsunpacker) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}
