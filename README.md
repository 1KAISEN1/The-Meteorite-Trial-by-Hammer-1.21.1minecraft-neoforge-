# Trial by Hammer

Minecraft 1.21.1 / NeoForge 的锻造主题内容模组。以陨铁、合金与锻造为核心，
目标是成为完整的 Create 附属。

> 当前处于早期开发阶段：工程骨架已就绪，游戏内还没有任何内容。

## 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 21（`gradle.properties` 里已锁定路径） |
| Gradle | 8.12（由 wrapper 提供，无需手动安装） |
| NeoForge | 21.1.252 |
| Create | 6.0.10 |

## 构建

```powershell
.\gradlew.bat build
```

产物位于 `build/libs/trial_by_hammer-<版本>.jar`。

首次构建会下载 Gradle 发行版并运行 NeoForm 反编译，耗时约 2 分钟；
之后的增量构建在数秒级。

## 开发运行

```powershell
.\gradlew.bat runClient
```

注意：`src/main/resources/META-INF/neoforge.mods.toml` 目前把 Create 声明为
必需依赖，而开发客户端不会自动加载 Create。启动前需要把
`libs/create-1.21.1-6.0.10.jar` 放进 `run/mods/`，否则 FML 会拒绝启动。

## 目录结构

```
src/main/java/com/trialbyhammer/    Java 源码
src/main/resources/                 资源与元数据
libs/                               Create 及拆出的内嵌依赖（编译用）
docs/                               设计文档
```

## 依赖说明

Create 6.0.10 把 Flywheel / Ponder / Registrate 以 jar-in-jar 形式内嵌。
这里把它们拆出来放在 `libs/` 并声明为 `compileOnly`，好处是：

- 不需要给构建脚本添加 Create 的 Maven 仓库；
- 不涉及 Registrate 的版本配对；
- 升级 Create 时只需替换 `libs/` 里的文件。

## 许可证

MIT，见 [LICENSE](LICENSE)。
