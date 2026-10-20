// Trial by Hammer —— Gradle 设置
//
// 这里集中声明"去哪里找插件"，避免每个子项目重复配置。
// NeoForged 的 Maven 必须排在 gradlePluginPortal 之前解析，否则
// ModDevGradle 可能拉到旧版本。

pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        gradlePluginPortal()
    }
}

rootProject.name = "trial_by_hammer"
