# Agent Guide — ReviewAtlas

This document is for AI agents and developers working on **ReviewAtlas**, an
IntelliJ Platform plugin for reviewing Git commits and uncommitted changes.

Read this file first, then:

- [README.md](README.md) — product usage, build, run instructions
- [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md) — pitfalls we already hit

---

## 1. Current Scope

Phase 1 + Java method-level semantic tree:

- ToolWindow `ReviewAtlas` on the right sidebar.
- Loads the latest 30 commits asynchronously, plus a Working Tree entry.
- Renders changed files as a compact directory tree.
- Parses changed Java files into a method-level tree:
  - `NEW` / `MODIFIED` / `DELETED`
  - parameter signature, `+/-` line counts (no start line displayed)
  - collapsed by default under file nodes
- Double-click method: opens the native diff scrolled to the method.
- Method context menu: `Open Diff`, `Jump to Source`, `Copy Reference`.
- `Copy Reference` copies `com.example.ClassName#methodName`.

Kotlin method parsing and call-graph analysis are **not implemented yet**.

---

## 2. Build & Environment

Requirements:

- JDK 21
- Gradle wrapper 9.x (the project uses Gradle 9.2.0)
- IntelliJ Platform Gradle Plugin 2.x
- IntelliJ IDEA Community 2024.2+ target

Typical commands:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\gradlew.bat compileKotlin
.\gradlew.bat buildPlugin
.\gradlew.bat runIde -PlocalIdePath="C:\path\to\IntelliJ IDEA 2024.2.6"
```

`-PlocalIdePath` is optional. Without it the build downloads IDEA Community
2024.2.6, which is slow on some networks. Prefer a local unpacked IDE when one
is available.

If Gradle cannot find JDK 21:

```powershell
.\gradlew.bat compileKotlin -Porg.gradle.java.installations.paths="C:\path\to\jdk-21"
```

Build output:

```text
build/distributions/ReviewAtlas-1.0.0.zip
```

---

## 3. Important Source Files

| File | Responsibility |
|---|---|
| `git/GitChangeLoader.kt` | Git4Idea history, changes, untracked files |
| `semantic/SemanticMethodExtractor.kt` | Java PSI method extraction and change detection |
| `model/MethodChange.kt` | Method status, signature, reference, lines |
| `model/ReviewDiffTarget.kt` | Diff target attached to a method DiffRequest |
| `util/DiffOpener.kt` | Opens file/method native diffs |
| `util/ReviewDiffScrollExtension.kt` | Scrolls a new diff viewer to the method |
| `util/MethodNavigator.kt` | Opens the current source method |
| `ui/ChangesTreeBuilder.kt` | File/directory/method tree model |
| `ui/ChangesTreeCellRenderer.kt` | Colors, icons, labels |
| `ui/ReviewAtlasPanel.kt` | ToolWindow, tasks, interactions |
| `resources/META-INF/plugin.xml` | ToolWindow and DiffExtension registration |

---

## 4. Hard Rules

### Threading

- Never run Git commands, file IO, or PSI parsing on the EDT.
- Use `Task.Backgroundable` for loading/analysis.
- Wrap VFS/PSI access in `ReadAction.compute`.
- Return to the EDT only for UI updates/navigation.
- `Task.Backgroundable` has no usable `cancel()` in this platform version;
  use generation counters and `disposed` checks instead.

### Semantic methods

- A method is changed **only if its own PSI text differs** between before/after.
- Do not decide method ownership from line ranges or diff fragments. Zero-length
  insertion fragments can land on a neighbouring method's closing brace.
- Normalize CRLF/LF before comparing method text.
- Keep method matching by `name + normalized parameter types`.
- Import, field, constant, and class-declaration changes must never mark an
  unchanged method.
- Java only for now; do not pretend Kotlin methods are supported.

### Tree UX

- Directory nodes may auto-expand.
- File nodes (and therefore method children) stay **collapsed** by default.
- Do not show method start-line numbers in the row.
- Do not add a directory path suffix to file rows.

### IntelliJ Diff

- Use `com.intellij.openapi.util.Pair` for platform keys. A Kotlin `Pair`
  causes `ClassCastException` when the platform reads it.
- `DiffUserDataKeys.SCROLL_TO_LINE` is read from the `DiffRequest`, but the
  initial “first change” policy can override it. Keep the
  `ReviewDiffScrollExtension` fallback that explicitly scrolls the created
  viewer.
- Unified and side-by-side viewers need different line mapping:
  - side-by-side: use the focused/side editor directly;
  - unified: `UnifiedDiffViewer.transferLineToOneside(...)`.
- `ChangeDiffRequestProducer.create(project, change, customData)` attaches
  custom keys to the produced `DiffRequest`.

---

## 5. Verification Checklist

Before considering a change complete:

1. `.\gradlew.bat compileKotlin`
2. `.\gradlew.bat buildPlugin`
3. Inspect the produced jar:
   - required classes present;
   - temporary smoke classes/activities absent;
   - `plugin.xml` has no `postStartupActivity` test hook.
4. For semantic changes, test at least:
   - import-only change → no methods;
   - class-constant-only change → no methods;
   - new import + new method → only the new method;
   - modified method body → only that method.
5. For Diff changes, test both side-by-side and unified if possible.
6. `git diff --check` must pass.
7. Do not commit `build/`, `.gradle/`, `.intellijPlatform/`, `.idea/`, or
   local proxy/JDK paths.

Temporary smoke hooks are useful (auto-open ToolWindow, log extraction
results), but must be removed before the final build.

---

## 6. Common Mistakes

- Using Kotlin `Pair` where IntelliJ `Pair` is required.
- Mapping changed lines to methods by line range.
- Treating a zero-length diff side as a changed line.
- Calling PSI without `ReadAction`.
- Expanding all tree nodes with `TreeUtil.expandAll`, including file method
  children.
- Forgetting that ToolWindow `id` is also the display title.
- Hardcoding local JDK/IDE paths or proxy configuration in committed files.
- Committing generated build artifacts.