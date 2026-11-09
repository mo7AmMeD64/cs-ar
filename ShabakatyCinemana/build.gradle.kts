version = 5

cloudstream {
    description = "لا يعمل خارج العراق او اي شبكة غير ايرثلنك"
    authors = listOf("Abodabodd")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie"
    )

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTZlAGH56cEnNEL93W3QqZWpUe8XR8i90olTA&s"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
}
