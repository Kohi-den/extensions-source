plugins {
    id("lib-multisrc")
}

baseVersionCode = 1

dependencies {
    implementation(project(":lib:voe-extractor"))
    implementation(project(":lib:streamtape-extractor"))
    implementation(project(":lib:dood-extractor"))
}
