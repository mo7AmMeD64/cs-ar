import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

version = 1

cloudstream {
    description = "بث مباشر للقنوات العربية والرياضية — Yacine TV Plus / احمد اولايف TV"
    authors = listOf("mo7AmMeD64")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://logo.twoapistack.work/img/topics/hot_now.png"
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
