import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "moe.nyamori"
version = extra["app.version"]!!

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Note, if you develop a library, you should use compose.desktop.common.
    // compose.desktop.currentOs should be used in launcher-sourceSet
    // (in a separate module for demo project and in testMain).
    // With compose.desktop.common you will also lose @Preview functionality
    implementation(compose.desktop.currentOs)
    implementation("org.apache.commons:commons-imaging:1.0.0-alpha5")
    implementation("commons-codec:commons-codec:1.17.1")
    implementation("org.apache.commons:commons-text:1.12.0")


    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.slf4j:jul-to-slf4j:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.7")
}


compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "QQ Mht2html"

            packageVersion = project.version as String
            description = "QQ Mht2html"
            copyright = "(C) 2022-2024 gyakkun. Some rights reserved."
            vendor = "gyakkun"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            // includeAllModules = true
            modules(
                "java.logging",
                "java.naming",
                "jdk.crypto.ec",
                "java.base",
                "java.desktop",
            )

            val iconsRoot = project.file("src/main/resources/drawables")

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
