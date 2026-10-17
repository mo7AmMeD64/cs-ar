import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask

version = 23

cloudstream {
    description = "AnimeDay — أنمي وكرتون مدبلج ومترجم"
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Anime",
        "TvSeries",
        "Movie",
        "Cartoon"
    )

    iconUrl = "https://www.anime-day.com/favicon.ico"
}

tasks.matching { it.name == "make" }.configureEach {
    (this as org.gradle.api.tasks.bundling.Zip).from("src/main/jniLibs") {
        into("lib")
    }
}

tasks.withType<CompileDexTask>().configureEach {
    val javaCompile = project.tasks.named("compileDebugJavaWithJavac")
    dependsOn(javaCompile)
    input.from(javaCompile)
}