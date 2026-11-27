import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

version = 2

cloudstream {
    description = "تاكد من انك تفتحه في العراق 🇮🇶 وعلى شبكة مدعومة"
    authors = listOf("Abodabodd")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie"
    )

    iconUrl = "https://raw.githubusercontent.com/mo7AmMeD64/cs-ar/main/repo/icons/Vodu.png"
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    tasks.withType<KotlinJvmCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}
