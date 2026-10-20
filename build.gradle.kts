// Trial by Hammer —— 构建脚本
//
// 设计目标：
//  1. 不用 NeoForge MDK 模板，避免把模板里的示例代码和用不上的配置带进来。
//  2. Create 及其内嵌依赖（Flywheel / Ponder / Registrate）以本地 jar 形式
//     参与编译。这样不依赖 registrate 的版本配对，也避免给构建脚本加一堆
//     Maven 仓库。升级 Create 时只需要换 libs/ 里的文件并改 gradle.properties。
//  3. 运行配置只保留 client / server，够用且启动快。

plugins {
    id("net.neoforged.moddev") version "2.0.147"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("mod_group").get()

java {
    // NeoForge 21.1 的字节码目标固定是 21，不要跟着本机 JDK 走。
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

neoForge {
    version = providers.gradleProperty("neo_version").get()

    runs {
        create("client") {
            client()
            // 开发时把日志级别调高一点，方便看注册和事件问题。
            systemProperty("forge.logging.markers", "")
            logLevel = org.slf4j.event.Level.DEBUG
        }
        create("server") {
            server()
            programArgument("--nogui")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        register(providers.gradleProperty("mod_id").get()) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // Create 本体。compileOnly 表示：编译时可见，但不打进我们的 jar，
    // 运行时由玩家的 mods 目录提供。
    compileOnly(files("libs/create-1.21.1-6.0.10.jar"))

    // Create 6.0.10 把这三个库以 jar-in-jar 形式内嵌，运行时由 FML 展开；
    // 但编译期它们不在 classpath 上，所以这里手动补进来。
    compileOnly(files("libs/flywheel-neoforge-1.21.1-1.0.6.jar"))
    compileOnly(files("libs/ponder-neoforge-1.0.82+mc1.21.1.jar"))
    compileOnly(files("libs/Registrate-MC1.21-1.3.0+67.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 打开所有 lint，早期把警告当敌人，省得后期还债。
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to providers.gradleProperty("mod_name").get(),
            "Implementation-Version" to project.version,
        )
    }
}
