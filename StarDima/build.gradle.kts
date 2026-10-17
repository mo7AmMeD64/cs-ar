import org.gradle.api.tasks.bundling.Zip

version = 15

cloudstream {
    description = "StarDima — ستارديما | أفلام ومسلسلات وكرتون مدبلج ومترجم."
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon",
        "AnimeMovie"
    )

    iconUrl = "https://stardima.app/logo.png"
}

tasks.named<Zip>("make") {
    from("src/main/assets/native") {
        into("native")
    }
}
