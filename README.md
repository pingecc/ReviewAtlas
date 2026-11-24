# ReviewAtlas

一个用于 **IntelliJ IDEA 2024.2+** 的代码 Review 插件（Phase 1 / MVP）。

它解决两个痛点：

1. 在独立的侧边栏 ToolWindow 中，按**严格的文件目录树** Review 任意历史 Git Commit（或当前未提交变更）；
2. 为 Phase 2 的“逻辑调用关系分析”预留清晰的 Git / 模型 / UI 分层。

> 当前实现 Phase 1 + Java 方法级语义树；调用关系图和 Mermaid/JCEF 尚未启动。

---

## 功能（Phase 1）

- 侧边栏 ToolWindow：`ReviewAtlas`
- 异步加载当前项目最近 30 条 Commit（`短 Hash + Message + Author`），支持刷新
- 额外提供 `Working Tree · uncommitted changes` 入口，Review 未提交变更（含 `git ls-files --others` 的未跟踪文件）
- 默认选中最新 Commit；Git 仓库晚于 ToolWindow 初始化时自动重试
- 按文件真实路径逐级分组渲染目录树（目录优先、忽略大小写排序）
- 自动压缩只有单个子目录、且自身没有文件的中间路径（如 `src/main/java/com/example/order`）
- 颜色 + 图标区分 `Added / Modified / Deleted / Moved`
- Java 方法级语义树：文件节点下展示变更方法，区分 `NEW / MODIFIED / DELETED`
- 方法节点显示参数签名、`+新增/-删除` 行数，不显示起始行号
- 文件下的方法树默认折叠，展开文件后查看
- 双击方法：打开 Diff 并定位到对应方法行
- 方法右键：`Open Diff`、`Jump to Source`、`Copy Reference`
- `Copy Reference` 复制 `com.example.OrderService#createOrder` 形式的完整引用
- 双击文件：打开 IDEA 原生 Diff 视图
- 文件右键菜单：`Open Diff`、`Jump to Source`、`Copy Relative Path`
- 键盘 `Enter`：文件执行 `Open Diff`，方法执行 `Open Diff`
- 双击目录：展开 / 收起

### 目录树压缩规则

- 只有一个子目录、且自身没有文件的中间目录，会合并成一行显示，例如：
  `src/main/java/com/example/order`
- 遇到**包含文件**的目录时停止合并，因此文件始终显示在其真实父目录下。
- 根节点不参与合并，避免只含一条路径时丢失第一层目录。
- 目录名后的数字表示该目录下所有变更文件数。

示例：

```text
src/main/java/com/example/order       3
  |- OrderController.java
  |- OrderService.java
  `- model/Order.java
```

---

## 方法级语义树（Java）

对每个 Java 变更文件，插件会：

1. 读取 before / after 版本内容；
2. 用 `PsiFileFactory` 解析为 Java PSI；
3. 用方法名 + 参数类型匹配 before / after 方法；
4. 直接比较匹配方法自身的 PSI 文本（统一 CRLF/LF）：
   - 文本相同 → 认为方法未变更，直接跳过；
   - 文本不同 → `MODIFIED`；
5. 判断：
   - `NEW`：after 中新增的方法；
   - `DELETED`：before 中存在、after 中不存在的方法；
   - 方法签名变化：先精确匹配签名；剩余同名方法在唯一匹配时合并为一个 `MODIFIED`，
     不会出现同名 `NEW + DELETED` 两个节点；
6. 用 `ComparisonManager.compareLines` 统计每个方法的 `+/-` 行数。

因此 import、类常量、字段、类声明、方法整体行号移动，都不会把未变化的方法标成 `MODIFIED`。

示例：

```text
OrderService.java
  createOrder(String)          MODIFIED  +12 -3
  validateOrder(Order)         NEW       +24
```

- 方法树默认折叠，文件节点展开后才显示方法；
- 双击方法：打开 Diff，并通过 `SCROLL_TO_LINE` 直接定位到方法行；
- 方法右键 `Jump to Source`：打开当前工作区源码的对应方法；
- 方法右键 `Copy Reference`：复制 `完整类名#方法名`；
- 删除的方法：Diff 定位到 before 侧方法行；
- 当前只支持 Java，Kotlin 暂不解析方法结构。

---

## 技术栈

| 项目 | 版本 / 说明 |
| --- | --- |
| 语言 | Kotlin 2.4.21 |
| 构建 | Gradle 9.2.0 + Kotlin DSL |
| IntelliJ Platform Gradle Plugin | `org.jetbrains.intellij.platform` **2.20.1** |
| 目标平台 | IntelliJ IDEA Community **2024.2.6**（`sinceBuild=242`，向上兼容） |
| JDK | **21**（IntelliJ Platform 2024.2+ 要求） |
| 内置插件依赖 | `com.intellij.modules.platform`、`Git4Idea`、`com.intellij.java` |

---

## 目录结构

```text
ReviewAtlas/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
├── gradlew / gradlew.bat
├── README.md
└── src/main/
    ├── kotlin/io/github/pingecc/reviewatlas/
    │   ├── git/
    │   │   └── GitChangeLoader.kt        # Git4Idea：最近 30 条提交、Change 列表、路径归一化
    │   ├── model/
    │   │   ├── CommitItem.kt             # Commit / Working Tree 统一模型
    │   │   ├── ChangeEntry.kt            # 单个变更文件（状态 + 相对路径 + methods）
    │   │   ├── MethodChange.kt           # 变更方法模型
    │   │   └── ReviewTreeNode.kt         # 目录 / 文件 / 方法树模型
    │   ├── semantic/
    │   │   └── SemanticMethodExtractor.kt# Java PSI：变更行 -> 变更方法
    │   ├── ui/
    │   │   ├── ReviewAtlasToolWindowFactory.kt
    │   │   ├── ReviewAtlasPanel.kt      # 主面板、Task.Backgroundable 调度、交互
    │   │   ├── CommitSelector.kt         # 下拉框 + 刷新按钮
    │   │   ├── ChangesTreeBuilder.kt     # Change -> 严格目录树
    │   │   └── ChangesTreeCellRenderer.kt # 图标 / 颜色 / 状态
    │   └── util/
    │       ├── DiffOpener.kt             # 后台构造 DiffRequest，EDT 打开原生 Diff
    │       ├── MethodNavigator.kt        # 后台定位当前源码方法，EDT 打开
    │       └── Edt.kt                    # ReadAction / invokeLater 封装
    └── resources/
        ├── META-INF/plugin.xml
        ├── META-INF/pluginIcon.svg
        └── icons/reviewAtlas.svg
```

---

## 环境要求

- JDK 21（推荐 Temurin 21）
- 能访问 Maven Central / JetBrains IntelliJ Repository（首次构建需要下载 IntelliJ Platform 2024.2.6）
- Windows 用户如果 `services.gradle.org` 访问缓慢，wrapper 已改用腾讯云镜像：
  `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl`

请确保 Gradle 使用 JDK 21（推荐把 `JAVA_HOME` 指向 JDK 21）：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
```

如果不想改 `JAVA_HOME`，也可以显式把 JDK 21 路径传给 Gradle：

```powershell
.\gradlew.bat compileKotlin -Porg.gradle.java.installations.paths="C:\path\to\jdk-21"
```

---

## 构建与验证

Windows PowerShell：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\gradlew.bat compileKotlin
.\gradlew.bat buildPlugin
```

macOS / Linux：

```bash
./gradlew compileKotlin
./gradlew buildPlugin
```

产物：

```text
build/distributions/ReviewAtlas-1.0.0.zip
```

### 离线 / 使用本地 IDE 构建

默认配置会从 JetBrains 仓库下载 IntelliJ IDEA Community 2024.2.6。如果本机已有解压好的同版本（或更新版本）IDE：

```powershell
.\gradlew.bat compileKotlin -PlocalIdePath="C:\path\to\IntelliJ IDEA 2024.2.6"
.\gradlew.bat runIde      -PlocalIdePath="C:\path\to\IntelliJ IDEA 2024.2.6"
```

> 使用更新的 IDE（例如 2025.x / 2026.x）编译时，仍建议最终用 2024.2.6 做一次兼容性验证。

---

## 启动沙箱 IDEA 测试

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\gradlew.bat runIde
```

Gradle 会：

1. 下载 / 解压目标 IDE；
2. 把插件安装到 `.intellijPlatform/sandbox/ReviewAtlas/IC-2024.2.6/plugins_runIde/`；
3. 启动一个独立的沙箱 IDEA（不会影响你日常使用的 IDE 配置）。

### 验证步骤

1. 在沙箱 IDEA 中打开一个 **有 Git 历史的 Java 项目**；
2. 点击右侧边栏的 **ReviewAtlas**；
3. 顶部下拉框应异步出现：
   - `Working Tree · uncommitted changes`
   - 最近 30 条 Commit（短 Hash + Message + Author）
4. 选择一个 Commit（默认选中最新 Commit）：
   - 主区域按目录树显示变更文件；
   - 新增 = 绿色，修改 = 蓝色，删除 = 红色，移动 = 紫色；
5. 双击文件 → 打开 IDEA 原生 Diff；
6. 展开文件 → 查看方法级语义树；
7. 双击方法 → 打开 Diff 并定位到方法；
8. 方法右键 → `Jump to Source` / `Copy Reference`；
9. 点击顶部刷新按钮 → 重新加载提交列表。

> 沙箱 IDE 的日志位置：
> `.intellijPlatform/sandbox/ReviewAtlas/IC-2024.2.6/log_runIde/idea.log`

---

## 线程与性能设计

- **所有 Git 命令**（`GitHistoryUtils.history`、`GitChangeUtils.getRevisionChanges`）都在
  `Task.Backgroundable.run()` 中执行，绝不在 EDT 上运行。
- **VFS / PSI 读取**（`VirtualFile` 解析、`ChangeListManager`、`GitCommit.changes`）统一包裹在
  `ReadAction.compute` 中。
- **方法级语义提取**在加载变更文件的后台任务中执行：读取 before/after → 解析 PSI → 映射变更方法，
  逐个文件检查取消状态。
- 耗时操作完成后，通过 `Task` 的 `onSuccess`（EDT）或
  `ApplicationManager.getApplication().invokeLater` 更新 UI。
- 使用 `generation` 计数丢弃过期结果：快速切换 Commit / 刷新时，旧任务的结果不会覆盖新结果。
- 树模型与 `Change` 对象只在 EDT 上被读取；Git loader 返回纯数据 `List<ChangeEntry>`。

---

## 已验证

- `compileKotlin` / `buildPlugin` 在 **IntelliJ IDEA Community 2024.2.6 + JDK 21** 下通过；
- `runIde` 沙箱实测：
  - `ReviewAtlas` ToolWindow 可正常创建（验证时由临时 Activity 自动打开）；
  - 加载到 3 条示例提交，默认选中最新提交；
  - 提交变更文件正确按目录分组（含新增 / 修改 / 删除状态）；
  - 原生 Diff 可正常打开（验证时由临时逻辑自动触发；正式交互为双击，含历史提交和删除文件）；
  - `Working Tree` 可读取已跟踪修改 + 未跟踪新文件；
  - 目录树压缩已用样例验证：`src/main/java/com/acme/order` 合并为一行，而包含 `build.txt` 的 `docs` 目录保持独立；
  - 方法级语义提取已用样例验证：
    - `Order.java` → `NEW:getStatus`、`NEW:setStatus`；
    - `OrderRepository.java` → `MODIFIED:save`、`NEW:findById`；
    - `ConstantsOnly.java` 只修改类常量 → 不生成方法节点（PSI 归属校验）；
    - `ImportOnly.java` 只新增 import → 不生成方法节点；
    - `AddMethod.java` 新增 import + 新方法 → 只生成 `NEW:added`，不会把相邻方法误标为修改；
    - `SignatureChange.java` 参数签名变化 → 只生成一个 `MODIFIED:process`，不会出现同名两个方法。

> 测试时曾临时加入启动后自动打开 ToolWindow 的 Activity，验证完成后已移除；正式插件由用户点击侧边栏按钮打开。

## 已知限制（Phase 1 + 语义树 v1）

- 方法级语义提取目前只支持 **Java**；Kotlin 文件仍只显示为文件节点。
- 方法跳转映射到**当前工作区**源码；历史 Commit 的代码可能已变化，找不到方法时回退到近似行。
- 删除的方法没有当前源码位置，会打开文件 Diff。
- 字段、import、注释等非方法变更目前不会单独生成节点。
- 多仓库项目会聚合各仓库的最近提交，总数仍限制为 30 条。
- 暂不支持按作者 / 分支 / 路径过滤。
- 调用关系图尚未实现（下一阶段）。

---

## 开发文档

- [AGENTS.md](AGENTS.md)：面向后续 Agent 的项目约束、构建方式和检查清单
- [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)：已经踩过的坑与修复记录

---

## 下一阶段规划

1. 在方法节点上接入 IDEA 原生 `Call Hierarchy`（调用者/被调用者）；
2. 可选：增加受影响测试列表；
3. 可选：构建轻量 `caller -> callee` 关系面板；
4. 最后再评估 Mermaid/JCEF 图形化，不作为第一优先级。