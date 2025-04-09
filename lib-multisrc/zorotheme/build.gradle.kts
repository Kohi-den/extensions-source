plugins {
    id("lib-multisrc")
}

baseVersionCode = 5

dependencies {
    api(project(":lib:megacloud-extractor"))
    api(project(":lib:streamtape-extractor"))
}
