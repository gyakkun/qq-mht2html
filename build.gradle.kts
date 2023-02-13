import org.jetbrains.compose.compose
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

group = "moe.nyamori"
version = extra["app.version"]!!

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
        withJava()
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.apache.commons:commons-imaging:1.0-alpha3")
                implementation("commons-codec:commons-codec:1.15")
                implementation("org.apache.commons:commons-lang3:3.12.0")

                // Loggin
                implementation("org.slf4j:slf4j-api:2.0.5")
                implementation("org.slf4j:jul-to-slf4j:2.0.5")
                implementation("ch.qos.logback:logback-classic:1.4.5")
            }
        }
        val jvmTest by getting
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "QQ Mht2html"

            packageVersion = project.version as String
            description = "QQ Mht2html"
            copyright = "(C) 2022 gyakkun. Some rights reserved."
            vendor = "gyakkun"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            // modules(
            //     "java.logging",
            //     "java.naming",
            //     "jdk.crypto.ec"
            // )

            val iconsRoot = project.file("src/jvmMain/resources/drawables")

            linux {
                iconFile.set(iconsRoot.resolve("qq-mht2html.png"))
            }

            windows {
                iconFile.set(iconsRoot.resolve("qq-mht2html.ico"))
                // Wondering what the heck is this? See : https://wixtoolset.org/documentation/manual/v3/howtos/general/generate_guids.html
                upgradeUuid = "92D39676-2715-4362-82D2-BE4A1923D4AC"
                menuGroup = packageName
                // perUserInstall = true
            }

            macOS {
                iconFile.set(iconsRoot.resolve("qq-mht2html.icns"))
            }

        }
    }
}
