plugins {
    id("lib-multisrc")
}

baseVersionCode = 6

dependencies {
    api(project(":lib:megacloud-extractor"))
    api(project(":lib:streamtape-extractor"))
}
