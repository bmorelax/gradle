ant.importBuild("build.xml")

task("intro") {
    dependsOn("hello")
    doLast {
        println("Hello, from Gradle")
    }
}
