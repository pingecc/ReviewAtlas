# ReviewAtlas 踩坑记录

本文记录 ReviewAtlas 开发过程中已经踩过、并且容易再次踩到的问题。  
新 Agent 接手时请先读 [AGENTS.md](../AGENTS.md)，再读本文。

---

## 1. IntelliJ Diff：Kotlin Pair 与 IntelliJ Pair

### 症状

双击方法打开 Diff 时直接抛异常：

```text
class kotlin.Pair cannot be cast to class com.intellij.openapi.util.Pair
```

### 原因

`DiffUserDataKeys.SCROLL_TO_LINE` 的类型是：

```java
Key<com.intellij.openapi.util.Pair<Side, Integer>>
```

但代码里用了 Kotlin 的：

```kotlin
kotlin.Pair(side, line)
```

### 修复

必须显式使用 IntelliJ 的 Pair：

```kotlin
DiffUserDataKeys.SCROLL_TO_LINE to
    com.intellij.openapi.util.Pair(side, line)
```

或者 import `com.intellij.openapi.util.Pair`，不要依赖 Kotlin 默认的 `Pair`。

### 教训

给平台 API 传 `Pair` / `Triple` 时，先确认它要的是平台类型还是 Kotlin 类型。  
这类错误编译期不会报，运行时才炸。

---

## 2. Diff 初始滚动会被“第一个变更块”覆盖

### 症状

设置了 `SCROLL_TO_LINE`，但 Diff 打开后仍然停在文件顶部或第一个变更处。

### 原因

Diff 的初始滚动顺序大致是：

```text
doScrollToChange
→ doScrollToLine
→ scrollToContext
→ scrollToPosition
→ scrollToFirstChange
```

如果 `SCROLL_TO_CHANGE` 被设置，或者编辑器已经被认为滚动过，
`SCROLL_TO_LINE` 可能不会生效。

### 修复

不要只依赖 `SCROLL_TO_LINE`。增加了：

- `ReviewDiffTarget`：把目标 `Side + line` 附加到 `DiffRequest`；
- `ReviewDiffScrollExtension`：在 viewer 创建后，显式滚动到目标行。

### 教训

Diff 的“初始滚动”和“打开后滚动”是两个不同阶段。  
对定位精度要求高时，保留一个 viewer 创建后的兜底滚动。

---

## 3. Unified 与 Side-by-Side 的行号不是一回事

### 症状

Side-by-side 下能定位，Unified 下方法跳转/定位失效。

### 原因

- Side-by-side：左右两侧各有一个 `DocumentContent`，行号就是该侧行号；
- Unified：只有一个合成编辑器，行号既不是 before 侧，也不是 after 侧。

### 修复

对 `UnifiedDiffViewer` 使用：

```kotlin
viewer.transferLineFromOneside(Side.RIGHT, unifiedLine)
```

把 unified 行号映射回 after 侧，再定位/解析方法。

### 教训

不能假设 Diff 编辑器里看到的行号等于某一侧文件的行号。  
写 Diff 导航时先判断 viewer 类型。

---

## 4. Diff 右键菜单注册位置

### 症状

把 Action Group 加到 `EditorPopupMenu`，但在 Diff 里右键看不到。

### 原因

Diff 编辑器安装了自己的 popup handler，使用：

```text
Diff.EditorPopupMenu
```

而不是普通编辑器的 `EditorPopupMenu`。

### 修复

```xml
<add-to-group group-id="Diff.EditorPopupMenu" anchor="last"/>
```

### 教训

先在平台源码/字节码中确认 Diff popup 使用的 group id，不要凭直觉加
`EditorPopupMenu`。

> 该功能后续被产品回退，但结论仍适用于任何 Diff 右键扩展。

---

## 5. 方法语义树：不要用行范围判断方法归属

### 症状

- 只改 import，某个方法被标成 `MODIFIED`；
- 只改类常量，唯一方法被标成 `MODIFIED`；
- 新增一个方法后，相邻方法也被标成 `MODIFIED`。

### 原因

1. 早期实现按“变更行是否落在方法行范围内”判断；
2. 方法新增/删除时，diff 在某侧会产生 `startLine == endLine` 的零长度片段；
3. 零长度片段如果被当作“变更行”，很容易落到相邻方法的右花括号或空行上，
   被 PSI 解析成相邻方法；
4. 行号整体移动也会放大误判。

### 错误思路

- 用行号交集判断；
- 用 zero-length 片段的插入点找方法；
- 认为“PSI 归属”能完全解决——如果插入点本身落在相邻方法范围内，仍会错。

### 最终方案

**直接比较方法自身的 PSI 文本。**

```text
beforeMethod.text == afterMethod.text
    → 方法未变，跳过
beforeMethod.text != afterMethod.text
    → MODIFIED
```

方法匹配规则：

```text
方法名 + 规范化后的参数类型
```

判断：

- before 无、after 有 → `NEW`
- before 有、after 无 → `DELETED`
- 两边都有且文本不同 → `MODIFIED`

### 必须保留的测试

| 场景 | 期望 |
|---|---|
| 只新增 import | 无方法节点 |
| 只修改类常量 | 无方法节点 |
| 只调整方法行号 | 无方法节点 |
| 新增 import + 新增方法 | 只有新方法 `NEW` |
| 修改方法体 | 只有该方法 `MODIFIED` |

### 教训

“变更行属于哪个方法”比“方法本身内容有没有变”更容易出错。  
语义 Review 应该以**方法文本是否变化**为准，而不是 diff 行号。

---

## 6. 零长度 diff 片段不能当作变更行

Diff 的一侧可能返回：

```text
startLine == endLine
```

这表示该侧没有实际内容，只是插入/删除位置。

不要拿这个位置去查找“所属方法”。  
否则新增方法很容易把上一个方法标成修改。

---

## 7. Detached PSI 的边界

历史 revision 的文本通过 `PsiFileFactory` 构造成临时 Java PSI：

- 方法结构、方法体、签名文本可用；
- 引用解析、类型解析不可靠；
- 足够做方法提取，不够做调用图。

### 教训

- 方法提取可以用 detached PSI；
- 调用关系应基于当前工作区真实 PSI，或复用平台 Call Hierarchy；
- 不要把“能解析语法”误认为“能解析引用”。

---

## 8. PSI 必须在 ReadAction / 后台执行

平台红线：

- 禁止 EDT 上跑 Git、文件 IO、PSI 解析；
- PSI 访问使用 `ReadAction.compute`；
- Git / diff 内容读取放 `Task.Backgroundable`；
- 最后回 EDT 做导航和 UI 更新。

### 教训

不要在 `actionPerformed` 里直接 `PsiManager.findFile(...)` 然后解析。
Action 应启动后台任务。

---

## 9. Tree 默认展开会展开文件下的方法

### 症状

方法树默认全部展开，页面非常长。

### 原因

`TreeUtil.expandAll(tree)` 会展开所有节点，包括 File 节点和 Method 子节点。

### 修复

只递归展开 Directory 节点：

```kotlin
private fun expandDirectoryNode(node, path) {
    if (node.userObject !is ReviewTreeNode.Directory) return
    tree.expandPath(path)
    // recurse children
}
```

### 教训

“展开目录”不等于“展开整棵树”。文件节点默认折叠是有意的产品行为。

---

## 10. ToolWindow 的 id 就是显示标题

### 症状

想把面板标题从 `Review Atlas` 改成 `ReviewAtlas`，但只改 README 没用。

### 原因

ToolWindow 的显示名称来自 `plugin.xml` 的：

```xml
<toolWindow id="Review Atlas" .../>
```

`id` 同时用于窗口标识和标题。

### 修复

```xml
<toolWindow id="ReviewAtlas" .../>
```

改 id 后，IDEA 可能保留旧的布局记录，需要重启或通过
`View → Tool Windows → ReviewAtlas` 打开。

### 教训

改标题前先确认 `id` 是否被代码/布局/快捷键引用。

---

## 11. 环境与构建

### Gradle / JDK

- IntelliJ Platform Gradle Plugin 2.20.1 需要 Gradle 9；
- 项目使用 Gradle 9.2.0 wrapper；
- IntelliJ Platform 2024.2+ 需要 JDK 21 toolchain；
- 本机没有 JDK 21 时，Gradle 会报 toolchain 找不到。

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\gradlew.bat buildPlugin -PlocalIdePath="C:\path\to\ideaIC-2024.2.6"
```

### 网络

- 某些网络访问 GitHub / JetBrains 仓库很慢；
- wrapper 使用腾讯云镜像；
- 本地已解压 IDE 时优先用 `-PlocalIdePath`，避免重复下载 600MB+；
- Git push 可能需要本地代理；代理只写本地 `.git/config`，不要提交。

### 编译前确认

- `./gradlew compileKotlin`
- `./gradlew buildPlugin`
- 检查 jar 中是否有意外类；
- 检查 `plugin.xml` 是否包含临时 smoke Activity。

---

## 12. 临时 Smoke 测试要清理

为了验证 ToolWindow / 语义树，曾临时加入：

- `postStartupActivity` 自动打开面板；
- `Logger.info` 输出方法提取结果；
- 临时测试用 sample 文件/commit。

这些只能用于本地验证，**最终包必须移除**：

- 删除 smoke Activity 文件；
- 删除 `plugin.xml` 中的 `postStartupActivity`；
- 删除临时日志；
- 重新 `clean buildPlugin`；
- `grep` 确认没有残留。

---

## 13. Git 提交前检查

```powershell
git diff --check
git status --short
```

不要提交：

- `build/`
- `.gradle/`
- `.intellijPlatform/`
- `.idea/`
- 本地 IDE / JDK 路径
- 代理配置或 token

---

## 14. 当前结论

- 方法级语义树：可用，按方法 PSI 文本判定；
- 方法 Diff 定位：`SCROLL_TO_LINE` + `ReviewDiffScrollExtension` 双保险；
- Unified / Side-by-Side：行号映射必须区分；
- 调用图：尚未实现，后续优先复用平台 Call Hierarchy，而不是自建完整图。