# Review Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An IntelliJ IDEA plugin that lists the files under review across all of a project's git roots, lets you mark each one reviewed, and advances to the next unreviewed file — filling the gap myflow's Gate B leaves when reviewing a staged, uncommitted diff.

**Architecture:** A right-docked tool window holds a `ChangesTree` of `ReviewItem`s produced by a scope resolver (staged / branch-vs-base / commit range) over every `GitRepository` in the project. Reviewed marks are content-addressed — a per-project `PersistentStateComponent` stores `rootPath|relPath -> contentHash`, so a file whose content later changes silently returns to the unreviewed set. All logic that can be pure (ordering, cursor advance, hashing, staged filtering, base-ref selection) lives in platform-free Kotlin and is unit tested; git4idea and Swing sit in thin adapters at the edges.

**Tech Stack:** Kotlin, Gradle with the IntelliJ Platform Gradle Plugin 2.x, JDK 21, IntelliJ IDEA Ultimate 2026.2, bundled `Git4Idea` plugin, JUnit 4.

## Global Constraints

- Plugin ID: `dev.tweety.reviewqueue`. Plugin name: `Review Queue`.
- Target platform: IntelliJ IDEA Ultimate `2026.2` (build `262.8665.258`). `sinceBuild = "262"`, no `untilBuild`.
- JDK/toolchain: 21. Kotlin JVM target: 21.
- Base package: `dev.tweety.reviewqueue`.
- Depends on the bundled plugin `Git4Idea`.
- **All tests use JUnit 4** (`org.junit.Test`, `org.junit.Assert.*`). The IntelliJ platform test framework is JUnit 4 based; mixing JUnit 5 into the same source set causes harness discovery problems.
- **The plugin never mutates a repository.** No `git add`, `commit`, `checkout`, `stash`, or any other write command. Read-only git queries only.
- Review state is per-project workspace state (`StoragePathMacros.WORKSPACE_FILE`). It is never written into the repository under review.
- These git4idea / platform APIs are **verified present** in build 262.8665.258 — use exactly these names:
  - `git4idea.repo.GitRepositoryManager.getInstance(project).repositories: List<GitRepository>`
  - `git4idea.repo.GitRepository.GIT_REPO_CHANGE: Topic<GitRepositoryChangeListener>`, callback `repositoryChanged(GitRepository)`
  - `git4idea.index.getStatus(project, root, paths, withUntracked, withIgnored, withRenames): List<GitFileStatus>` (Kotlin top-level function; JVM class `git4idea.index.GitIndexStatusUtilKt`)
  - `git4idea.index.GitFileStatus` with `index: Char`, `workTree: Char`, `path: FilePath`, `origPath: FilePath?`, `isTracked()`, `getStagedStatus(): FileStatus?`
  - `git4idea.index.createChange(project, root, status, ContentVersion.HEAD, ContentVersion.STAGED): Change` (Kotlin top-level function; JVM class `git4idea.index.GitStageDiffUtilKt`)
  - `git4idea.changes.GitChangeUtils.getThreeDotDiffOrThrow(repository, base, head): Collection<Change>`
  - `git4idea.changes.GitChangeUtils.getDiff(repository, oldRev, newRev, detectRenames): Collection<Change>`
  - `com.intellij.openapi.vcs.changes.ChangeListListener.TOPIC`
  - `com.intellij.openapi.vcs.changes.ui.ChangesTree(project: Project, showCheckboxes: Boolean, highlightProblems: Boolean)` — abstract
  - `com.intellij.openapi.vcs.changes.ui.TreeModelBuilder(project, groupingPolicyFactory)`
  - `com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer.create(project, change)`
  - `com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain(producers: List<Producer>, index: Int)`
  - `com.intellij.diff.editor.ChainDiffVirtualFile(chain, title)`
  - `com.intellij.diff.editor.DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, focus)`
- **Do not use** `git4idea.changes.GitChangeUtils.getStagedChanges` — it returns `GitDiffChange`, which does not extend `Change` and carries no content revisions.
- There are **no Next/Previous actions**. Marking a file is the only thing that advances the cursor; clicking a row is the only other navigation.

## File Structure

```
build.gradle.kts                                          Gradle build, platform + Git4Idea deps
settings.gradle.kts                                       Root project name
gradle.properties                                         Version/platform coordinates
src/main/resources/META-INF/plugin.xml                    Plugin descriptor, tool window, actions, notification group
src/main/kotlin/dev/tweety/reviewqueue/
  model/ReviewKey.kt          ReviewKey — (rootPath, relPath) identity + storage key
  model/ReviewItem.kt         ReviewItem — key + contentHash; pure queue element
  model/ReviewScope.kt        ReviewScope sealed type — Staged / BranchVsBase / CommitRange
  core/ContentHasher.kt       Pure content -> hash
  core/ReviewOrdering.kt      Pure root-grouped, path-sorted ordering
  core/ReviewCursor.kt        Pure cursor math: next-unreviewed with single wrap
  core/StagedFilter.kt        Pure "is this porcelain status staged?" predicate
  core/BaseRefResolver.kt     Pure base-ref selection for BranchVsBase
  state/ReviewStateService.kt Persistent per-project reviewed marks
  git/GitReviewSource.kt      git4idea adapter: ReviewScope -> List<Change> per root
  queue/ReviewQueueService.kt Project service: owns scope, queue, cursor; VCS listeners
  ui/ReviewQueueTree.kt       ChangesTree subclass with reviewed decoration
  ui/ReviewQueuePanel.kt      Tool window content: tree + toolbar + progress label
  ui/ReviewQueueToolWindowFactory.kt
  ui/ReviewDiffOpener.kt      Opens a Change in a reused diff editor tab
  ui/ScopeSelector.kt         Scope dropdown + ref/range input with validation
  actions/MarkReviewedAction.kt
  actions/RefreshQueueAction.kt
  actions/ResetAllAction.kt
  notify/CompletionNotifier.kt
src/test/kotlin/dev/tweety/reviewqueue/
  core/ContentHasherTest.kt
  core/ReviewOrderingTest.kt
  core/ReviewCursorTest.kt
  core/StagedFilterTest.kt
  core/BaseRefResolverTest.kt
  state/ReviewStateServiceTest.kt
  notify/BranchNameParserTest.kt
  git/GitReviewSourceIntegrationTest.kt   Heavy test, real temp git repo
```

---

### Task 1: Project scaffold that builds, runs, and tests

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`
- Create: `src/main/resources/META-INF/plugin.xml`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/core/ContentHasherTest.kt` (placeholder sanity test, replaced in Task 3)

**Interfaces:**
- Consumes: nothing.
- Produces: a Gradle build where `./gradlew test` and `./gradlew runIde` both work; plugin ID `dev.tweety.reviewqueue`.

- [ ] **Step 1: Create the Gradle wrapper**

The wrapper is not checked in yet and `gradle` is not on this machine's PATH. Generate it from the Gradle distribution IntelliJ ships, or download once:

```bash
cd /Users/tweety53/Projects/intellij-review-queue
curl -sL https://services.gradle.org/distributions/gradle-8.14-bin.zip -o /tmp/gradle.zip
unzip -q -o /tmp/gradle.zip -d /tmp/gradle-dist
/tmp/gradle-dist/gradle-8.14/bin/gradle wrapper --gradle-version 8.14
```

Expected: `gradlew`, `gradlew.bat`, and `gradle/wrapper/` appear.

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "review-queue"
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
pluginGroup = dev.tweety
pluginVersion = 0.1.0
platformVersion = 2026.2
org.gradle.jvmargs = -Xmx2g
kotlin.stdlib.default.dependency = false
```

`kotlin.stdlib.default.dependency = false` is required: the IntelliJ platform bundles its own Kotlin stdlib, and adding a second one causes classloader conflicts at runtime.

- [ ] **Step 4: Write `build.gradle.kts`**

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion").get())
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.tweety.reviewqueue"
        name = "Review Queue"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main { kotlin.srcDir("src/main/kotlin") }
    test { kotlin.srcDir("src/test/kotlin") }
}
```

If `org.jetbrains.intellij.platform` version `2.9.0` fails to resolve, run
`curl -s "https://plugins.gradle.org/api/gradle/plugin/use/org.jetbrains.intellij.platform" | head -40`
and substitute the latest 2.x version reported. Do not drop to the 1.x `org.jetbrains.intellij` plugin — its DSL differs entirely from everything in this plan.

- [ ] **Step 5: Write `.gitignore`**

```gitignore
.gradle/
build/
.idea/
*.iml
out/
```

- [ ] **Step 6: Write the minimal `src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>dev.tweety.reviewqueue</id>
    <name>Review Queue</name>
    <vendor>tweety53</vendor>

    <description><![CDATA[
        Review the files in a staged diff, a branch-vs-base diff, or a commit range one at a time,
        marking each as reviewed. Reviewed marks are content-addressed, so editing a file returns it
        to the unreviewed set.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>Git4Idea</depends>

    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup id="Review Queue" displayType="BALLOON"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 7: Write a sanity test proving the harness runs**

Create `src/test/kotlin/dev/tweety/reviewqueue/core/ContentHasherTest.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import org.junit.Assert.assertTrue
import org.junit.Test

class ContentHasherTest {
    @Test
    fun `test harness runs`() {
        assertTrue(true)
    }
}
```

- [ ] **Step 8: Run the build and tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 1 test passed. The first run downloads the 2026.2 platform and takes several minutes.

- [ ] **Step 9: Verify the plugin loads in a real IDE**

Run: `./gradlew runIde`
Expected: a sandbox IntelliJ IDEA window opens. Confirm under Settings → Plugins that "Review Queue" is listed and enabled, then close the sandbox IDE.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "chore: scaffold Review Queue plugin build"
```

---

### Task 2: Review identity and content hashing

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/model/ReviewKey.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/model/ReviewItem.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/core/ContentHasher.kt`
- Modify: `src/test/kotlin/dev/tweety/reviewqueue/core/ContentHasherTest.kt` (replace the sanity test)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class ReviewKey(val rootPath: String, val relPath: String)` with `fun storageKey(): String`
  - `data class ReviewItem(val key: ReviewKey, val contentHash: String)`
  - `object ContentHasher { fun hash(content: String?, revisionFallback: String?): String }`

- [ ] **Step 1: Write the failing tests**

Replace the contents of `src/test/kotlin/dev/tweety/reviewqueue/core/ContentHasherTest.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHasherTest {
    @Test
    fun `same content hashes the same`() {
        assertEquals(ContentHasher.hash("hello", null), ContentHasher.hash("hello", null))
    }

    @Test
    fun `different content hashes differently`() {
        assertNotEquals(ContentHasher.hash("hello", null), ContentHasher.hash("world", null))
    }

    @Test
    fun `null content falls back to the revision string`() {
        val a = ContentHasher.hash(null, "abc123")
        val b = ContentHasher.hash(null, "abc123")
        val c = ContentHasher.hash(null, "def456")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `null content and null revision is stable and distinct`() {
        assertEquals(ContentHasher.hash(null, null), ContentHasher.hash(null, null))
        assertNotEquals(ContentHasher.hash(null, null), ContentHasher.hash("", null))
    }

    @Test
    fun `review key storage key joins root and path`() {
        assertEquals("/repo|src/Main.kt", ReviewKey("/repo", "src/Main.kt").storageKey())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*ContentHasherTest*'`
Expected: FAIL — unresolved references `ContentHasher` and `ReviewKey`.

- [ ] **Step 3: Write `ReviewKey.kt`**

```kotlin
package dev.tweety.reviewqueue.model

/** Identity of a file under review: its git root and the path relative to that root. */
data class ReviewKey(val rootPath: String, val relPath: String) {
    /** Key used in persisted state. Stable across sessions. */
    fun storageKey(): String = "$rootPath|$relPath"

    companion object {
        fun fromStorageKey(key: String): ReviewKey? {
            val sep = key.indexOf('|')
            if (sep <= 0 || sep == key.length - 1) return null
            return ReviewKey(key.substring(0, sep), key.substring(sep + 1))
        }
    }
}
```

- [ ] **Step 4: Write `ReviewItem.kt`**

```kotlin
package dev.tweety.reviewqueue.model

/**
 * One element of the review queue. Deliberately free of platform types so queue ordering,
 * cursor math and review classification can be unit tested without an IDE.
 */
data class ReviewItem(val key: ReviewKey, val contentHash: String)
```

- [ ] **Step 5: Write `ContentHasher.kt`**

```kotlin
package dev.tweety.reviewqueue.core

import java.security.MessageDigest

/**
 * Hashes the "after" side of a change. The hash is what a reviewed mark is stored against, so a
 * file whose content later changes no longer matches its stored mark and returns to the queue.
 */
object ContentHasher {
    private const val NO_CONTENT = "\u0000no-content"

    fun hash(content: String?, revisionFallback: String?): String {
        val payload = when {
            content != null -> "c:$content"
            revisionFallback != null -> "r:$revisionFallback"
            else -> NO_CONTENT
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests '*ContentHasherTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add review identity model and content hashing"
```

---

### Task 3: Persistent reviewed marks

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/state/ReviewStateService.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/state/ReviewStateServiceTest.kt`

**Interfaces:**
- Consumes: `ReviewKey`, `ReviewItem` from Task 2.
- Produces: `class ReviewStateService : PersistentStateComponent<ReviewStateService.State>` with
  - `fun isReviewed(item: ReviewItem): Boolean`
  - `fun markReviewed(item: ReviewItem)`
  - `fun unmark(key: ReviewKey)`
  - `fun resetAll()`
  - `fun prune(liveKeys: Set<ReviewKey>)`
  - `fun reviewedCount(items: List<ReviewItem>): Int`
  - `companion object { fun getInstance(project: Project): ReviewStateService }`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/tweety/reviewqueue/state/ReviewStateServiceTest.kt`:

```kotlin
package dev.tweety.reviewqueue.state

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStateServiceTest {
    private fun item(path: String, hash: String) = ReviewItem(ReviewKey("/repo", path), hash)

    @Test
    fun `unmarked item is not reviewed`() {
        val service = ReviewStateService()
        assertFalse(service.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `marked item is reviewed`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        assertTrue(service.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `changed content drops the reviewed mark`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        assertFalse(service.isReviewed(item("a.kt", "h2")))
    }

    @Test
    fun `restoring the original content restores the mark`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        assertFalse(service.isReviewed(item("a.kt", "h2")))
        assertTrue(service.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `unmark removes the entry`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        service.unmark(ReviewKey("/repo", "a.kt"))
        assertFalse(service.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `resetAll clears everything`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        service.markReviewed(item("b.kt", "h2"))
        service.resetAll()
        assertFalse(service.isReviewed(item("a.kt", "h1")))
        assertFalse(service.isReviewed(item("b.kt", "h2")))
    }

    @Test
    fun `prune drops keys not in the live set`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        service.markReviewed(item("b.kt", "h2"))
        service.prune(setOf(ReviewKey("/repo", "a.kt")))
        assertTrue(service.isReviewed(item("a.kt", "h1")))
        assertFalse(service.isReviewed(item("b.kt", "h2")))
    }

    @Test
    fun `state round trips`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        val restored = ReviewStateService()
        restored.loadState(service.state)
        assertTrue(restored.isReviewed(item("a.kt", "h1")))
    }

    @Test
    fun `reviewedCount counts only matching hashes`() {
        val service = ReviewStateService()
        service.markReviewed(item("a.kt", "h1"))
        service.markReviewed(item("b.kt", "h2"))
        val items = listOf(item("a.kt", "h1"), item("b.kt", "CHANGED"), item("c.kt", "h3"))
        assertEquals(1, service.reviewedCount(items))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*ReviewStateServiceTest*'`
Expected: FAIL — unresolved reference `ReviewStateService`.

- [ ] **Step 3: Write `ReviewStateService.kt`**

```kotlin
package dev.tweety.reviewqueue.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey

/**
 * Per-project reviewed marks, stored in workspace state so they survive restarts and never touch
 * the repository. A file counts as reviewed only while its current content hash equals the hash
 * recorded when it was marked.
 */
@Service(Service.Level.PROJECT)
@State(name = "ReviewQueueState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewStateService : PersistentStateComponent<ReviewStateService.State> {

    class State {
        @JvmField
        var reviewed: MutableMap<String, String> = mutableMapOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun isReviewed(item: ReviewItem): Boolean =
        myState.reviewed[item.key.storageKey()] == item.contentHash

    fun markReviewed(item: ReviewItem) {
        myState.reviewed[item.key.storageKey()] = item.contentHash
    }

    fun unmark(key: ReviewKey) {
        myState.reviewed.remove(key.storageKey())
    }

    fun resetAll() {
        myState.reviewed.clear()
    }

    /** Drops stored marks for files no longer present in any queue, bounding state growth. */
    fun prune(liveKeys: Set<ReviewKey>) {
        val live = liveKeys.mapTo(mutableSetOf()) { it.storageKey() }
        myState.reviewed.keys.retainAll(live)
    }

    fun reviewedCount(items: List<ReviewItem>): Int = items.count { isReviewed(it) }

    companion object {
        fun getInstance(project: Project): ReviewStateService = project.service()
    }
}
```

The `project.service()` call resolves through `import com.intellij.openapi.components.service` — already in the import list above.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*ReviewStateServiceTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: persist content-addressed reviewed marks per project"
```

---

### Task 4: Queue ordering and cursor advance

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewOrdering.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/core/ReviewCursor.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewOrderingTest.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewCursorTest.kt`

**Interfaces:**
- Consumes: `ReviewItem`, `ReviewKey` from Task 2.
- Produces:
  - `object ReviewOrdering { fun order(items: List<ReviewItem>, rootOrder: List<String>): List<ReviewItem> }`
  - `object ReviewCursor {`
    `fun firstUnreviewed(items: List<ReviewItem>, isReviewed: (ReviewItem) -> Boolean): Int?`
    `fun nextUnreviewed(items: List<ReviewItem>, from: Int, isReviewed: (ReviewItem) -> Boolean): Int?`
    `fun relocate(items: List<ReviewItem>, previousKey: ReviewKey?, previousIndex: Int): Int?` `}`

- [ ] **Step 1: Write the failing ordering test**

Create `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewOrderingTest.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewOrderingTest {
    private fun item(root: String, path: String) = ReviewItem(ReviewKey(root, path), "h")

    @Test
    fun `groups by root order then sorts by path`() {
        val items = listOf(
            item("/b", "z.kt"),
            item("/a", "z.kt"),
            item("/b", "a.kt"),
            item("/a", "a.kt"),
        )
        val ordered = ReviewOrdering.order(items, listOf("/a", "/b"))
        assertEquals(
            listOf("/a|a.kt", "/a|z.kt", "/b|a.kt", "/b|z.kt"),
            ordered.map { it.key.storageKey() },
        )
    }

    @Test
    fun `roots absent from rootOrder sort last, alphabetically`() {
        val items = listOf(item("/z", "a.kt"), item("/a", "a.kt"), item("/m", "a.kt"))
        val ordered = ReviewOrdering.order(items, listOf("/a"))
        assertEquals(listOf("/a", "/m", "/z"), ordered.map { it.key.rootPath })
    }

    @Test
    fun `ordering is stable across repeated calls`() {
        val items = listOf(item("/a", "b.kt"), item("/a", "a.kt"))
        val first = ReviewOrdering.order(items, listOf("/a"))
        val second = ReviewOrdering.order(first, listOf("/a"))
        assertEquals(first.map { it.key }, second.map { it.key })
    }
}
```

- [ ] **Step 2: Write the failing cursor test**

Create `src/test/kotlin/dev/tweety/reviewqueue/core/ReviewCursorTest.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewCursorTest {
    private fun item(path: String) = ReviewItem(ReviewKey("/r", path), "h")

    private val items = listOf(item("a.kt"), item("b.kt"), item("c.kt"), item("d.kt"))

    private fun reviewed(vararg paths: String): (ReviewItem) -> Boolean =
        { it.key.relPath in paths.toSet() }

    @Test
    fun `firstUnreviewed finds the first unreviewed item`() {
        assertEquals(1, ReviewCursor.firstUnreviewed(items, reviewed("a.kt")))
    }

    @Test
    fun `firstUnreviewed returns null when everything is reviewed`() {
        assertNull(ReviewCursor.firstUnreviewed(items, reviewed("a.kt", "b.kt", "c.kt", "d.kt")))
    }

    @Test
    fun `firstUnreviewed returns null for an empty queue`() {
        assertNull(ReviewCursor.firstUnreviewed(emptyList(), reviewed()))
    }

    @Test
    fun `nextUnreviewed moves forward past the current item`() {
        assertEquals(2, ReviewCursor.nextUnreviewed(items, from = 1, isReviewed = reviewed("a.kt", "b.kt")))
    }

    @Test
    fun `nextUnreviewed wraps to an earlier unreviewed item`() {
        assertEquals(0, ReviewCursor.nextUnreviewed(items, from = 2, isReviewed = reviewed("b.kt", "c.kt", "d.kt")))
    }

    @Test
    fun `nextUnreviewed never returns the item it started from`() {
        assertNull(ReviewCursor.nextUnreviewed(items, from = 1, isReviewed = reviewed("a.kt", "c.kt", "d.kt")))
    }

    @Test
    fun `nextUnreviewed returns null when everything is reviewed`() {
        assertNull(ReviewCursor.nextUnreviewed(items, from = 0, isReviewed = reviewed("a.kt", "b.kt", "c.kt", "d.kt")))
    }

    @Test
    fun `relocate keeps the cursor on the same path`() {
        val rebuilt = listOf(item("x.kt"), item("b.kt"))
        assertEquals(1, ReviewCursor.relocate(rebuilt, ReviewKey("/r", "b.kt"), previousIndex = 1))
    }

    @Test
    fun `relocate falls to the same index when the path is gone`() {
        val rebuilt = listOf(item("x.kt"), item("y.kt"), item("z.kt"))
        assertEquals(1, ReviewCursor.relocate(rebuilt, ReviewKey("/r", "b.kt"), previousIndex = 1))
    }

    @Test
    fun `relocate clamps to the last item when the queue shrank`() {
        val rebuilt = listOf(item("x.kt"))
        assertEquals(0, ReviewCursor.relocate(rebuilt, ReviewKey("/r", "d.kt"), previousIndex = 3))
    }

    @Test
    fun `relocate returns null for an empty queue`() {
        assertNull(ReviewCursor.relocate(emptyList(), ReviewKey("/r", "a.kt"), previousIndex = 0))
    }
}
```

- [ ] **Step 3: Run both tests to verify they fail**

Run: `./gradlew test --tests '*ReviewOrderingTest*' --tests '*ReviewCursorTest*'`
Expected: FAIL — unresolved references `ReviewOrdering` and `ReviewCursor`.

- [ ] **Step 4: Write `ReviewOrdering.kt`**

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem

/**
 * Deterministic queue order: git roots in the order the repository manager reports them, then
 * path-sorted within each root. Stability matters — the cursor is an index into this order.
 */
object ReviewOrdering {
    fun order(items: List<ReviewItem>, rootOrder: List<String>): List<ReviewItem> {
        val rank = rootOrder.withIndex().associate { (i, root) -> root to i }
        return items.sortedWith(
            compareBy<ReviewItem> { rank[it.key.rootPath] ?: Int.MAX_VALUE }
                .thenBy { it.key.rootPath }
                .thenBy { it.key.relPath }
        )
    }
}
```

- [ ] **Step 5: Write `ReviewCursor.kt`**

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey

/**
 * Cursor math for the queue. Marking a file advances to the next unreviewed one, wrapping past the
 * end exactly once so files above the cursor are not stranded, and never landing back on the item
 * the move started from.
 */
object ReviewCursor {

    fun firstUnreviewed(items: List<ReviewItem>, isReviewed: (ReviewItem) -> Boolean): Int? =
        items.indexOfFirst { !isReviewed(it) }.takeIf { it >= 0 }

    fun nextUnreviewed(items: List<ReviewItem>, from: Int, isReviewed: (ReviewItem) -> Boolean): Int? {
        if (items.isEmpty()) return null
        for (offset in 1..items.size) {
            val index = (from + offset) % items.size
            if (index == from) continue
            if (!isReviewed(items[index])) return index
        }
        return null
    }

    /**
     * Places the cursor after the queue was rebuilt: same file if it survived, otherwise the item
     * that now occupies the old position, clamped into range.
     */
    fun relocate(items: List<ReviewItem>, previousKey: ReviewKey?, previousIndex: Int): Int? {
        if (items.isEmpty()) return null
        if (previousKey != null) {
            val same = items.indexOfFirst { it.key == previousKey }
            if (same >= 0) return same
        }
        return previousIndex.coerceIn(0, items.size - 1)
    }
}
```

- [ ] **Step 6: Run both tests to verify they pass**

Run: `./gradlew test --tests '*ReviewOrderingTest*' --tests '*ReviewCursorTest*'`
Expected: PASS, 14 tests total.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add deterministic queue ordering and cursor advance"
```

---

### Task 5: Staged filtering and base-ref selection

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/core/StagedFilter.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/core/BaseRefResolver.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/model/ReviewScope.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/core/StagedFilterTest.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/core/BaseRefResolverTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `object StagedFilter { fun isStaged(indexStatus: Char): Boolean }`
  - `object BaseRefResolver { fun resolve(explicitBase: String?, trackedBranch: String?, fallbackRef: String?): String? }`
  - `sealed interface ReviewScope` with `object Staged`, `data class BranchVsBase(val explicitBase: String?)`, `data class CommitRange(val from: String, val to: String)`, plus `fun ReviewScope.displayName(): String` and `object CommitRangeValidator { fun validate(from: String, to: String): String? }` returning an error message or `null`.

- [ ] **Step 1: Write the failing staged-filter test**

Create `src/test/kotlin/dev/tweety/reviewqueue/core/StagedFilterTest.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedFilterTest {
    @Test
    fun `modified, added, deleted, renamed and copied index states are staged`() {
        listOf('M', 'A', 'D', 'R', 'C').forEach {
            assertTrue("index '$it' should count as staged", StagedFilter.isStaged(it))
        }
    }

    @Test
    fun `unmodified index is not staged`() {
        assertFalse(StagedFilter.isStaged(' '))
    }

    @Test
    fun `untracked and ignored are not staged`() {
        assertFalse(StagedFilter.isStaged('?'))
        assertFalse(StagedFilter.isStaged('!'))
    }

    @Test
    fun `unmerged index state is not staged`() {
        assertFalse(StagedFilter.isStaged('U'))
    }
}
```

- [ ] **Step 2: Write the failing base-ref test**

Create `src/test/kotlin/dev/tweety/reviewqueue/core/BaseRefResolverTest.kt`:

```kotlin
package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.CommitRangeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BaseRefResolverTest {
    @Test
    fun `explicit base wins`() {
        assertEquals("develop", BaseRefResolver.resolve("develop", "origin/main", "origin/HEAD"))
    }

    @Test
    fun `tracked branch is used when no explicit base`() {
        assertEquals("origin/main", BaseRefResolver.resolve(null, "origin/main", "origin/HEAD"))
    }

    @Test
    fun `fallback is used when nothing else is known`() {
        assertEquals("origin/HEAD", BaseRefResolver.resolve(null, null, "origin/HEAD"))
    }

    @Test
    fun `blank explicit base is ignored`() {
        assertEquals("origin/main", BaseRefResolver.resolve("   ", "origin/main", "origin/HEAD"))
    }

    @Test
    fun `null when nothing resolves`() {
        assertNull(BaseRefResolver.resolve(null, null, null))
    }

    @Test
    fun `commit range validator rejects blanks`() {
        assertNotNull(CommitRangeValidator.validate("", "HEAD"))
        assertNotNull(CommitRangeValidator.validate("HEAD", "  "))
    }

    @Test
    fun `commit range validator rejects shell metacharacters`() {
        assertNotNull(CommitRangeValidator.validate("HEAD; rm -rf /", "HEAD"))
    }

    @Test
    fun `commit range validator accepts ordinary refs`() {
        assertNull(CommitRangeValidator.validate("origin/develop", "HEAD"))
        assertNull(CommitRangeValidator.validate("a1b2c3d", "HEAD~3"))
    }
}
```

- [ ] **Step 3: Run both tests to verify they fail**

Run: `./gradlew test --tests '*StagedFilterTest*' --tests '*BaseRefResolverTest*'`
Expected: FAIL — unresolved references `StagedFilter`, `BaseRefResolver`, `CommitRangeValidator`.

- [ ] **Step 4: Write `StagedFilter.kt`**

```kotlin
package dev.tweety.reviewqueue.core

/**
 * Decides whether a git porcelain index status means "staged". Operates on the index (first)
 * column of `git status --porcelain`, which is what [git4idea.index.GitFileStatus.index] carries.
 */
object StagedFilter {
    private val STAGED_STATES = setOf('M', 'A', 'D', 'R', 'C', 'T')

    fun isStaged(indexStatus: Char): Boolean = indexStatus in STAGED_STATES
}
```

- [ ] **Step 5: Write `BaseRefResolver.kt`**

```kotlin
package dev.tweety.reviewqueue.core

/**
 * Chooses the base ref for a branch-vs-base review. The actual merge-base handling is left to
 * git's three-dot diff; this only decides which ref to compare against.
 */
object BaseRefResolver {
    fun resolve(explicitBase: String?, trackedBranch: String?, fallbackRef: String?): String? =
        explicitBase?.takeIf { it.isNotBlank() }
            ?: trackedBranch?.takeIf { it.isNotBlank() }
            ?: fallbackRef?.takeIf { it.isNotBlank() }
}
```

- [ ] **Step 6: Write `ReviewScope.kt`**

```kotlin
package dev.tweety.reviewqueue.model

/** What the review queue is built from. */
sealed interface ReviewScope {
    /** The git index against HEAD — myflow Gate B. */
    data object Staged : ReviewScope

    /** The working branch against a base ref, using git's three-dot (merge-base) semantics. */
    data class BranchVsBase(val explicitBase: String? = null) : ReviewScope

    /** An explicit two-ref range. */
    data class CommitRange(val from: String, val to: String) : ReviewScope
}

fun ReviewScope.displayName(): String = when (this) {
    is ReviewScope.Staged -> "Staged"
    is ReviewScope.BranchVsBase -> "Branch vs base"
    is ReviewScope.CommitRange -> "Commit range"
}

/** Validates commit-range input at entry time rather than at resolution time. */
object CommitRangeValidator {
    private val FORBIDDEN = charArrayOf(';', '&', '|', '`', '$', '\n', '\r', ' ')

    fun validate(from: String, to: String): String? {
        if (from.isBlank()) return "Enter a starting ref"
        if (to.isBlank()) return "Enter an ending ref"
        if (from.any { it in FORBIDDEN } || to.any { it in FORBIDDEN }) {
            return "Refs must not contain whitespace or shell metacharacters"
        }
        return null
    }
}
```

- [ ] **Step 7: Run both tests to verify they pass**

Run: `./gradlew test --tests '*StagedFilterTest*' --tests '*BaseRefResolverTest*'`
Expected: PASS, 12 tests total.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add staged filtering, base-ref selection and scope model"
```

---

### Task 6: git4idea adapter resolving a scope to changes

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/git/GitReviewSource.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/git/GitReviewSourceIntegrationTest.kt`

**Interfaces:**
- Consumes: `ReviewScope`, `StagedFilter`, `BaseRefResolver`, `ContentHasher`, `ReviewKey`, `ReviewItem`.
- Produces:
  - `data class RootResult(val rootPath: String, val changes: List<Change>, val error: String?)`
  - `class GitReviewSource(private val project: Project) { fun resolve(scope: ReviewScope): List<RootResult>; fun rootOrder(): List<String> }`
  - `object ChangeMapper { fun toItem(rootPath: String, change: Change): ReviewItem? }`

- [ ] **Step 1: Write the failing integration test**

Create `src/test/kotlin/dev/tweety/reviewqueue/git/GitReviewSourceIntegrationTest.kt`:

```kotlin
package dev.tweety.reviewqueue.git

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.tweety.reviewqueue.model.ReviewScope
import java.io.File

class GitReviewSourceIntegrationTest : HeavyPlatformTestCase() {

    private lateinit var repoDir: File

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
        return output
    }

    override fun setUp() {
        super.setUp()
        repoDir = File(project.basePath!!)
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        File(repoDir, "kept.txt").writeText("original\n")
        git("add", "kept.txt")
        git("commit", "-m", "initial")

        File(repoDir, "kept.txt").writeText("staged change\n")
        File(repoDir, "added.txt").writeText("brand new\n")
        File(repoDir, "untracked.txt").writeText("not staged\n")
        git("add", "kept.txt", "added.txt")

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
        ProjectLevelVcsManager.getInstance(project).apply {
            setDirectoryMappings(
                listOf(com.intellij.openapi.vcs.VcsDirectoryMapping(repoDir.absolutePath, "Git"))
            )
            waitForInitialized()
        }
    }

    fun testStagedScopeListsOnlyStagedFiles() {
        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        assertEquals(1, results.size)
        assertNull(results[0].error)

        val paths = results[0].changes.mapNotNull { it.afterRevision?.file?.name }.sorted()
        assertEquals(listOf("added.txt", "kept.txt"), paths)
    }

    fun testStagedScopeProducesHashableItems() {
        val results = GitReviewSource(project).resolve(ReviewScope.Staged)
        val items = results[0].changes.mapNotNull { ChangeMapper.toItem(results[0].rootPath, it) }
        assertEquals(2, items.size)
        items.forEach { assertTrue(it.contentHash.isNotBlank()) }
    }

    fun testUnknownRefInCommitRangeYieldsErrorNotException() {
        val results = GitReviewSource(project).resolve(ReviewScope.CommitRange("nope123", "HEAD"))
        assertEquals(1, results.size)
        assertNotNull(results[0].error)
        assertTrue(results[0].changes.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*GitReviewSourceIntegrationTest*'`
Expected: FAIL — unresolved references `GitReviewSource` and `ChangeMapper`.

- [ ] **Step 3: Write `GitReviewSource.kt`**

```kotlin
package dev.tweety.reviewqueue.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import dev.tweety.reviewqueue.core.BaseRefResolver
import dev.tweety.reviewqueue.core.ContentHasher
import dev.tweety.reviewqueue.core.StagedFilter
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import git4idea.changes.GitChangeUtils
import git4idea.index.ContentVersion
import git4idea.index.createChange
import git4idea.index.getStatus
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/** Per-root outcome. A failing root reports [error] instead of taking the whole queue down. */
data class RootResult(val rootPath: String, val changes: List<Change>, val error: String?)

/**
 * Translates a [ReviewScope] into platform [Change] objects, one result per git root. Read-only:
 * every git command issued here is a query.
 */
class GitReviewSource(private val project: Project) {

    fun rootOrder(): List<String> = repositories().map { it.root.path }

    fun resolve(scope: ReviewScope): List<RootResult> = repositories().map { repository ->
        val rootPath = repository.root.path
        try {
            RootResult(rootPath, resolveInRoot(repository, scope), null)
        } catch (e: VcsException) {
            RootResult(rootPath, emptyList(), e.message ?: "Failed to read changes")
        }
    }

    private fun repositories(): List<GitRepository> =
        GitRepositoryManager.getInstance(project).repositories

    private fun resolveInRoot(repository: GitRepository, scope: ReviewScope): List<Change> =
        when (scope) {
            is ReviewScope.Staged -> resolveStaged(repository)
            is ReviewScope.BranchVsBase -> resolveBranchVsBase(repository, scope.explicitBase)
            is ReviewScope.CommitRange ->
                GitChangeUtils.getDiff(repository, scope.from, scope.to, true)?.toList()
                    ?: throw VcsException("Could not diff ${scope.from}..${scope.to}")
        }

    private fun resolveStaged(repository: GitRepository): List<Change> {
        val statuses = getStatus(project, repository.root, emptyList(), false, false, true)
        return statuses
            .filter { it.isTracked() && StagedFilter.isStaged(it.index) }
            .map { createChange(project, repository.root, it, ContentVersion.HEAD, ContentVersion.STAGED) }
    }

    private fun resolveBranchVsBase(repository: GitRepository, explicitBase: String?): List<Change> {
        val tracked = repository.currentBranch?.findTrackedBranch(repository)?.name
        val base = BaseRefResolver.resolve(explicitBase, tracked, "origin/HEAD")
            ?: throw VcsException("No base ref: the branch tracks nothing and origin/HEAD is unset")
        val head = repository.currentBranch?.name
            ?: throw VcsException("Detached HEAD — choose a commit range instead")
        return GitChangeUtils.getThreeDotDiffOrThrow(repository, base, head).toList()
    }
}

/** Maps a platform [Change] onto the pure queue element used everywhere else. */
object ChangeMapper {
    fun toItem(rootPath: String, change: Change): ReviewItem? {
        val revision = change.afterRevision ?: change.beforeRevision ?: return null
        val absolutePath = revision.file.path
        val relPath = absolutePath.removePrefix(rootPath).removePrefix("/")
        val content = runCatching { change.afterRevision?.content }.getOrNull()
        val fallback = change.afterRevision?.revisionNumber?.asString()
            ?: change.beforeRevision?.revisionNumber?.asString()
        val marker = if (change.afterRevision == null) "deleted:$fallback" else fallback
        return ReviewItem(ReviewKey(rootPath, relPath), ContentHasher.hash(content, marker))
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*GitReviewSourceIntegrationTest*'`
Expected: PASS, 3 tests. This test is slow (it boots a real project fixture).

If `getStatus` or `createChange` fail to resolve as top-level functions, import their JVM facade instead:
`import git4idea.index.GitIndexStatusUtilKt.getStatus` is not valid Kotlin — instead confirm the package with
`javap -cp "/Applications/IntelliJ IDEA.app/Contents/plugins/vcs-git/lib/vcs-git.jar" git4idea.index.GitIndexStatusUtilKt`
and adjust the import to the package the facade reports (`git4idea.index`).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: resolve review scopes to changes via git4idea"
```

---

### Task 7: Queue service wiring scope, state and refresh

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueServiceTest.kt`

**Interfaces:**
- Consumes: `GitReviewSource`, `ChangeMapper`, `RootResult`, `ReviewStateService`, `ReviewOrdering`, `ReviewCursor`, `ReviewScope`.
- Produces:
  - `data class QueueSnapshot(val items: List<ReviewItem>, val cursor: Int?, val reviewedCount: Int, val errors: Map<String, String>, val scope: ReviewScope)`
  - `class ReviewQueueService(project: Project)` with `fun snapshot(): QueueSnapshot`, `fun setScope(scope: ReviewScope)`, `fun refresh()`, `fun markCurrentReviewed()`, `fun toggleReviewed(key: ReviewKey)`, `fun selectByKey(key: ReviewKey)`, `fun resetAll()`, `fun changeFor(key: ReviewKey): Change?`, `fun addListener(listener: () -> Unit, parent: Disposable)`, `companion object { fun getInstance(project: Project): ReviewQueueService }`
  - `object QueueAssembler { fun assemble(results: List<RootResult>, rootOrder: List<String>): Pair<List<ReviewItem>, Map<String, String>> }`

- [ ] **Step 1: Write the failing test for the pure assembler**

Create `src/test/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueServiceTest.kt`:

```kotlin
package dev.tweety.reviewqueue.queue

import dev.tweety.reviewqueue.git.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueServiceTest {
    @Test
    fun `assemble collects errors per root and keeps other roots listed`() {
        val results = listOf(
            RootResult("/a", emptyList(), "boom"),
            RootResult("/b", emptyList(), null),
        )
        val (items, errors) = QueueAssembler.assemble(results, listOf("/a", "/b"))
        assertTrue(items.isEmpty())
        assertEquals(mapOf("/a" to "boom"), errors)
    }

    @Test
    fun `assemble returns no errors when every root succeeds`() {
        val results = listOf(RootResult("/a", emptyList(), null))
        val (_, errors) = QueueAssembler.assemble(results, listOf("/a"))
        assertTrue(errors.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*ReviewQueueServiceTest*'`
Expected: FAIL — unresolved reference `QueueAssembler`.

- [ ] **Step 3: Write `ReviewQueueService.kt`**

```kotlin
package dev.tweety.reviewqueue.queue

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import dev.tweety.reviewqueue.core.ReviewCursor
import dev.tweety.reviewqueue.core.ReviewOrdering
import dev.tweety.reviewqueue.git.ChangeMapper
import dev.tweety.reviewqueue.git.GitReviewSource
import dev.tweety.reviewqueue.git.RootResult
import dev.tweety.reviewqueue.model.ReviewItem
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.state.ReviewStateService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

data class QueueSnapshot(
    val items: List<ReviewItem>,
    val cursor: Int?,
    val reviewedCount: Int,
    val errors: Map<String, String>,
    val scope: ReviewScope,
)

/** Turns per-root results into an ordered queue plus a per-root error map. */
object QueueAssembler {
    fun assemble(
        results: List<RootResult>,
        rootOrder: List<String>,
    ): Pair<List<ReviewItem>, Map<String, String>> {
        val errors = results.mapNotNull { r -> r.error?.let { r.rootPath to it } }.toMap()
        val items = results.flatMap { result ->
            result.changes.mapNotNull { ChangeMapper.toItem(result.rootPath, it) }
        }
        return ReviewOrdering.order(items, rootOrder) to errors
    }
}

/**
 * Owns the active scope, the ordered queue and the cursor, and rebuilds on VCS change events so
 * a fix round that rewrites files returns exactly those files to the unreviewed set.
 */
@Service(Service.Level.PROJECT)
class ReviewQueueService(private val project: Project) {

    private val source = GitReviewSource(project)
    private val state get() = ReviewStateService.getInstance(project)
    private val listeners = mutableListOf<() -> Unit>()

    private var scope: ReviewScope = ReviewScope.Staged
    private var items: List<ReviewItem> = emptyList()
    private var errors: Map<String, String> = emptyMap()
    private var changesByKey: Map<ReviewKey, Change> = emptyMap()
    private var cursor: Int? = null

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleRefresh()
        })
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { scheduleRefresh() })
    }

    fun snapshot(): QueueSnapshot =
        QueueSnapshot(items, cursor, state.reviewedCount(items), errors, scope)

    fun setScope(scope: ReviewScope) {
        this.scope = scope
        refresh()
    }

    fun refresh() {
        val previousKey = cursor?.let { items.getOrNull(it)?.key }
        val previousIndex = cursor ?: 0

        val results = source.resolve(scope)
        val rootOrder = source.rootOrder()
        val (newItems, newErrors) = QueueAssembler.assemble(results, rootOrder)

        items = newItems
        errors = newErrors
        changesByKey = results.flatMap { result ->
            result.changes.mapNotNull { change ->
                ChangeMapper.toItem(result.rootPath, change)?.let { it.key to change }
            }
        }.toMap()

        state.prune(items.mapTo(mutableSetOf()) { it.key })

        cursor = ReviewCursor.relocate(items, previousKey, previousIndex)
            ?: ReviewCursor.firstUnreviewed(items) { state.isReviewed(it) }
        if (previousKey == null) {
            cursor = ReviewCursor.firstUnreviewed(items) { state.isReviewed(it) } ?: cursor
        }
        fireChanged()
    }

    fun markCurrentReviewed() {
        val index = cursor ?: return
        val item = items.getOrNull(index) ?: return
        state.markReviewed(item)
        cursor = ReviewCursor.nextUnreviewed(items, index) { state.isReviewed(it) } ?: index
        fireChanged()
    }

    fun toggleReviewed(key: ReviewKey) {
        val item = items.firstOrNull { it.key == key } ?: return
        if (state.isReviewed(item)) state.unmark(key) else state.markReviewed(item)
        fireChanged()
    }

    fun selectByKey(key: ReviewKey) {
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0) {
            cursor = index
            fireChanged()
        }
    }

    fun resetAll() {
        state.resetAll()
        cursor = ReviewCursor.firstUnreviewed(items) { state.isReviewed(it) }
        fireChanged()
    }

    fun isReviewed(item: ReviewItem): Boolean = state.isReviewed(item)

    fun changeFor(key: ReviewKey): Change? = changesByKey[key]

    fun addListener(listener: () -> Unit, parent: Disposable) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    private fun scheduleRefresh() {
        ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
    }

    private fun fireChanged() {
        listeners.toList().forEach { it() }
    }

    companion object {
        fun getInstance(project: Project): ReviewQueueService = project.service()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*ReviewQueueServiceTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 5: Run the whole suite to catch regressions**

Run: `./gradlew test`
Expected: PASS, all tests from Tasks 2–7.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add review queue service with scope, cursor and live refresh"
```

---

### Task 8: Opening diffs in a reused editor tab

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewDiffOpener.kt`

**Interfaces:**
- Consumes: `ReviewQueueService.changeFor`, `ReviewQueueService.snapshot`, `ReviewKey`.
- Produces: `object ReviewDiffOpener { fun open(project: Project, key: ReviewKey) }`

- [ ] **Step 1: Write `ReviewDiffOpener.kt`**

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.queue.ReviewQueueService

/**
 * Opens the queue's current file in the standard diff viewer. The chain holds every file in the
 * queue with the current one selected, so the diff tab is reused rather than accumulating tabs.
 */
object ReviewDiffOpener {

    fun open(project: Project, key: ReviewKey) {
        val service = ReviewQueueService.getInstance(project)
        val snapshot = service.snapshot()

        val withChanges = snapshot.items.mapNotNull { item ->
            service.changeFor(item.key)?.let { item.key to it }
        }
        if (withChanges.isEmpty()) return

        val producers = withChanges.map { (_, change) ->
            ChangeDiffRequestProducer.create(project, change)
        }
        val index = withChanges.indexOfFirst { it.first == key }.coerceAtLeast(0)

        val chain = ChangeDiffRequestChain(producers, index)
        val file = ChainDiffVirtualFile(chain, "Review Queue")
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Behaviour is verified in Task 9, once there is a UI to click.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: open queue diffs in a reused editor tab"
```

---

### Task 9: Tool window with the review tree and progress

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueueTree.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueuePanel.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueueToolWindowFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: `ReviewQueueService`, `QueueSnapshot`, `ReviewItem`, `ReviewKey`.
- Produces:
  - `class ReviewQueueTree(project: Project, service: ReviewQueueService) : ChangesTree` with `fun refreshFrom(snapshot: QueueSnapshot)` and `fun selectedKey(): ReviewKey?`
  - `class ReviewQueuePanel(project: Project) : SimpleToolWindowPanel`
  - `class ReviewQueueToolWindowFactory : ToolWindowFactory`

- [ ] **Step 1: Write `ReviewQueueTree.kt`**

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangeNodeDecorator
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.tree.TreeUtil
import dev.tweety.reviewqueue.model.ReviewKey
import dev.tweety.reviewqueue.queue.QueueSnapshot
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/**
 * The queue as a platform changes tree: repo grouping, path shortening and file icons come from
 * the platform; the reviewed marker is the only thing this class adds.
 */
class ReviewQueueTree(
    private val project: Project,
    private val service: ReviewQueueService,
) : ChangesTree(project, false, false) {

    private var reviewedKeys: Set<String> = emptySet()
    private var rootPaths: List<String> = emptyList()

    init {
        setEmptyText("No files in the current review scope")
    }

    override fun rebuildTree() {
        refreshFrom(service.snapshot())
    }

    fun refreshFrom(snapshot: QueueSnapshot) {
        reviewedKeys = snapshot.items
            .filter { service.isReviewed(it) }
            .mapTo(mutableSetOf()) { it.key.storageKey() }
        rootPaths = snapshot.items.map { it.key.rootPath }.distinct()

        val changes: List<Change> = snapshot.items.mapNotNull { service.changeFor(it.key) }
        val model: DefaultTreeModel = TreeModelBuilder(project, grouping)
            .setChanges(changes, ReviewedDecorator())
            .build()
        updateTreeModel(model)

        val cursorKey = snapshot.cursor?.let { snapshot.items.getOrNull(it)?.key }
        if (cursorKey != null) selectKey(cursorKey)
    }

    /** The queue key for a change, resolved against the roots currently in the tree. */
    fun keyFor(change: Change): ReviewKey? {
        val path = (change.afterRevision ?: change.beforeRevision)?.file?.path ?: return null
        val root = rootPaths.filter { path.startsWith(it) }.maxByOrNull { it.length } ?: return null
        return ReviewKey(root, path.removePrefix(root).removePrefix("/"))
    }

    fun selectedKey(): ReviewKey? {
        val node = selectionPath?.lastPathComponent as? ChangesBrowserNode<*> ?: return null
        val change = node.userObject as? Change ?: return null
        return keyFor(change)
    }

    private fun selectKey(key: ReviewKey) {
        val root = model.root as? TreeNode ?: return
        val match = TreeUtil.treeNodeTraverser(root)
            .traverse()
            .filter(ChangesBrowserNode::class.java)
            .find { node -> (node.userObject as? Change)?.let { keyFor(it) } == key }
            ?: return
        TreeUtil.selectNode(this, match)
    }

    /** Appends the reviewed marker; everything else about the row is the platform's rendering. */
    private inner class ReviewedDecorator : ChangeNodeDecorator {
        override fun decorate(change: Change, component: SimpleColoredComponent, isShowFlatten: Boolean) {
            val key = keyFor(change) ?: return
            if (key.storageKey() in reviewedKeys) {
                component.append("  ✓ reviewed", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        override fun preDecorate(change: Change, renderer: ChangesBrowserNodeRenderer, isShowFlatten: Boolean) = Unit
    }
}
```

- [ ] **Step 2: Write `ReviewQueuePanel.kt`**

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.JPanel
import java.awt.BorderLayout

/** Tool window content: the queue tree, a toolbar, and a progress label. */
class ReviewQueuePanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val service = ReviewQueueService.getInstance(project)
    private val tree = ReviewQueueTree(project, service)
    private val progress = JBLabel("0 / 0 reviewed").apply { border = JBUI.Borders.empty(4, 8) }
    private val errorLabel = JBLabel("").apply { border = JBUI.Borders.empty(0, 8, 4, 8) }

    init {
        val group = ActionManager.getInstance().getAction("ReviewQueue.Toolbar") as DefaultActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val bottom = JPanel(BorderLayout()).apply {
            add(progress, BorderLayout.NORTH)
            add(errorLabel, BorderLayout.SOUTH)
        }
        val content = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        setContent(content)

        tree.addSelectionListener {
            tree.selectedKey()?.let { key ->
                service.selectByKey(key)
                ReviewDiffOpener.open(project, key)
            }
        }

        service.addListener(::update, this)
        service.refresh()
    }

    private fun update() {
        val snapshot = service.snapshot()
        tree.refreshFrom(snapshot)
        progress.text = "${snapshot.reviewedCount} / ${snapshot.items.size} reviewed" +
            "  •  ${snapshot.scope.displayName()}"
        errorLabel.text = snapshot.errors.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        errorLabel.isVisible = snapshot.errors.isNotEmpty()
    }
}
```

- [ ] **Step 3: Write `ReviewQueueToolWindowFactory.kt`**

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ReviewQueueToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewQueuePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
```

- [ ] **Step 4: Register the tool window and an empty toolbar group in `plugin.xml`**

Add inside the existing `<extensions defaultExtensionNs="com.intellij">` block:

```xml
        <toolWindow id="Review Queue"
                    anchor="right"
                    factoryClass="dev.tweety.reviewqueue.ui.ReviewQueueToolWindowFactory"/>
```

And add a sibling `<actions>` block after `</extensions>`:

```xml
    <actions>
        <group id="ReviewQueue.Toolbar" class="com.intellij.openapi.actionSystem.DefaultActionGroup"/>
    </actions>
```

- [ ] **Step 5: Verify the tool window renders against a real worktree**

Run: `./gradlew runIde`

In the sandbox IDE, open one of the myflow apply worktrees that has staged changes, e.g.:

```bash
ls -d /Users/tweety53/Projects/gymie/.worktrees/openspec-*
```

Expected: the "Review Queue" tool window appears on the right and lists the staged files grouped by repository, with a `0 / N reviewed` label.

- [ ] **Step 6: Verify diffs open and the tab is reused**

Still in the sandbox IDE, click several different files in the Review Queue tool window.

Expected: each click shows that file's diff, and the editor keeps a single "Review Queue" diff tab rather than opening one tab per file. Close the sandbox IDE.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add review queue tool window with tree and progress"
```

---


### Task 10: Toolbar actions — mark reviewed, refresh, reset all

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/MarkReviewedAction.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/RefreshQueueAction.kt`
- Create: `src/main/kotlin/dev/tweety/reviewqueue/actions/ResetAllAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: `ReviewQueueService`.
- Produces: three `AnAction` classes registered into the `ReviewQueue.Toolbar` group with IDs `ReviewQueue.MarkReviewed`, `ReviewQueue.Refresh`, `ReviewQueue.ResetAll`.

- [ ] **Step 1: Write `MarkReviewedAction.kt`**

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Marks the current file reviewed and advances to the next unreviewed one. */
class MarkReviewedAction : AnAction("Mark Reviewed", "Mark the current file reviewed and go to the next unreviewed file", AllIcons.Actions.Checked) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val snapshot = project?.let { ReviewQueueService.getInstance(it).snapshot() }
        e.presentation.isEnabled = snapshot?.cursor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewQueueService.getInstance(project).markCurrentReviewed()
    }
}
```

- [ ] **Step 2: Write `RefreshQueueAction.kt`**

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Re-resolves and re-hashes the queue on demand, for changes that arrive without a VCS event. */
class RefreshQueueAction : AnAction("Refresh", "Re-read the review scope", AllIcons.Actions.Refresh) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        ReviewQueueService.getInstance(project).refresh()
    }
}
```

- [ ] **Step 3: Write `ResetAllAction.kt`**

```kotlin
package dev.tweety.reviewqueue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import dev.tweety.reviewqueue.queue.ReviewQueueService

/** Clears every stored reviewed mark for this project, after confirmation. */
class ResetAllAction : AnAction("Reset All", "Clear every reviewed mark in this project", AllIcons.Actions.Rollback) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "Clear every reviewed mark in this project?",
            "Reset Review Progress",
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) {
            ReviewQueueService.getInstance(project).resetAll()
        }
    }
}
```

- [ ] **Step 4: Register the actions in `plugin.xml`**

Replace the `<actions>` block from Task 8 with:

```xml
    <actions>
        <group id="ReviewQueue.Toolbar" class="com.intellij.openapi.actionSystem.DefaultActionGroup">
            <action id="ReviewQueue.MarkReviewed"
                    class="dev.tweety.reviewqueue.actions.MarkReviewedAction"
                    text="Mark Reviewed"
                    description="Mark the current file reviewed and go to the next unreviewed file"/>
            <action id="ReviewQueue.Refresh"
                    class="dev.tweety.reviewqueue.actions.RefreshQueueAction"
                    text="Refresh"
                    description="Re-read the review scope"/>
            <action id="ReviewQueue.ResetAll"
                    class="dev.tweety.reviewqueue.actions.ResetAllAction"
                    text="Reset All"
                    description="Clear every reviewed mark in this project"/>
        </group>
    </actions>
```

- [ ] **Step 5: Verify marking advances and survives a restart**

Run: `./gradlew runIde`

In a worktree with several staged files: click the first file, press Mark Reviewed repeatedly.

Expected: each press moves the selection to the next unreviewed file and the progress label counts up. Then close the sandbox IDE, run `./gradlew runIde` again, reopen the same project.

Expected: the previously marked files are still counted as reviewed.

Then edit one marked file in the IDE and re-stage it (`git add <file>` in that worktree).

Expected: the progress count drops by one and that file is unreviewed again.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add mark-reviewed, refresh and reset-all toolbar actions"
```

---

### Task 11: Scope selector with validation

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/ui/ScopeSelector.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/ui/ReviewQueuePanel.kt`

**Interfaces:**
- Consumes: `ReviewScope`, `CommitRangeValidator`, `ReviewQueueService.setScope`.
- Produces: `class ScopeSelector(project: Project) : ComboBoxAction` registered as `ReviewQueue.Scope` in the toolbar group.

- [ ] **Step 1: Write `ScopeSelector.kt`**

```kotlin
package dev.tweety.reviewqueue.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import dev.tweety.reviewqueue.model.CommitRangeValidator
import dev.tweety.reviewqueue.model.ReviewScope
import dev.tweety.reviewqueue.model.displayName
import dev.tweety.reviewqueue.queue.ReviewQueueService
import javax.swing.JComponent

/** Toolbar dropdown choosing the review scope, prompting for refs where a scope needs them. */
class ScopeSelector : ComboBoxAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val scope = project?.let { ReviewQueueService.getInstance(it).snapshot().scope }
        e.presentation.text = scope?.displayName() ?: "Scope"
    }

    override fun createPopupActionGroup(button: JComponent, context: com.intellij.openapi.actionSystem.DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(SetStagedAction())
        group.add(SetBranchVsBaseAction())
        group.add(SetCommitRangeAction())
        return group
    }

    private class SetStagedAction : AnAction("Staged") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.getData(CommonDataKeys.PROJECT) ?: return
            ReviewQueueService.getInstance(project).setScope(ReviewScope.Staged)
        }
    }

    private class SetBranchVsBaseAction : AnAction("Branch vs Base…") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.getData(CommonDataKeys.PROJECT) ?: return
            val base = Messages.showInputDialog(
                project,
                "Base ref (leave empty to use the tracked branch):",
                "Branch vs Base",
                null,
            ) ?: return
            ReviewQueueService.getInstance(project)
                .setScope(ReviewScope.BranchVsBase(base.takeIf { it.isNotBlank() }))
        }
    }

    private class SetCommitRangeAction : AnAction("Commit Range…") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.getData(CommonDataKeys.PROJECT) ?: return
            val from = Messages.showInputDialog(
                project, "From ref:", "Commit Range", null, "HEAD~1",
                object : InputValidator {
                    override fun checkInput(input: String) = CommitRangeValidator.validate(input, "HEAD") == null
                    override fun canClose(input: String) = checkInput(input)
                },
            ) ?: return
            val to = Messages.showInputDialog(
                project, "To ref:", "Commit Range", null, "HEAD",
                object : InputValidator {
                    override fun checkInput(input: String) = CommitRangeValidator.validate(from, input) == null
                    override fun canClose(input: String) = checkInput(input)
                },
            ) ?: return
            ReviewQueueService.getInstance(project).setScope(ReviewScope.CommitRange(from, to))
        }
    }
}
```

- [ ] **Step 2: Register the selector as the first toolbar item in `plugin.xml`**

Add as the first child of the `ReviewQueue.Toolbar` group, before `ReviewQueue.MarkReviewed`:

```xml
            <action id="ReviewQueue.Scope"
                    class="dev.tweety.reviewqueue.ui.ScopeSelector"
                    text="Scope"
                    description="Choose what the review queue lists"/>
```

- [ ] **Step 3: Verify all three scopes**

Run: `./gradlew runIde`

In a myflow apply worktree:
1. Leave the scope on **Staged** — expect the staged files.
2. Switch to **Branch vs Base** with an empty base — expect the full branch diff against its tracked branch.
3. Switch to **Commit Range** with `HEAD~1` → `HEAD` — expect the last commit's files.
4. Enter a commit range containing a space or `;` — expect the dialog to refuse the input.
5. Switch back to **Staged** — expect the reviewed marks made in step 1 to still be shown.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add review scope selector with ref validation"
```

---

### Task 12: Completion notification with clipboard handoff

**Files:**
- Create: `src/main/kotlin/dev/tweety/reviewqueue/notify/CompletionNotifier.kt`
- Create: `src/test/kotlin/dev/tweety/reviewqueue/notify/BranchNameParserTest.kt`
- Modify: `src/main/kotlin/dev/tweety/reviewqueue/queue/ReviewQueueService.kt`

**Interfaces:**
- Consumes: `QueueSnapshot`, `GitRepositoryManager`.
- Produces:
  - `object BranchNameParser { fun changeName(branch: String?): String? }`
  - `class CompletionNotifier(project: Project) { fun onSnapshot(snapshot: QueueSnapshot) }`

- [ ] **Step 1: Write the failing branch-parser test**

Create `src/test/kotlin/dev/tweety/reviewqueue/notify/BranchNameParserTest.kt`:

```kotlin
package dev.tweety.reviewqueue.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchNameParserTest {
    @Test
    fun `extracts the change name from an openspec branch`() {
        assertEquals("add-widgets", BranchNameParser.changeName("openspec/add-widgets"))
    }

    @Test
    fun `keeps nested segments`() {
        assertEquals("add-widgets/fix-1", BranchNameParser.changeName("openspec/add-widgets/fix-1"))
    }

    @Test
    fun `returns null for other branches`() {
        assertNull(BranchNameParser.changeName("develop"))
        assertNull(BranchNameParser.changeName("feature/openspec-ish"))
    }

    @Test
    fun `returns null for null or bare prefix`() {
        assertNull(BranchNameParser.changeName(null))
        assertNull(BranchNameParser.changeName("openspec/"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*BranchNameParserTest*'`
Expected: FAIL — unresolved reference `BranchNameParser`.

- [ ] **Step 3: Write `CompletionNotifier.kt`**

```kotlin
package dev.tweety.reviewqueue.notify

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.tweety.reviewqueue.queue.QueueSnapshot
import git4idea.repo.GitRepositoryManager
import java.awt.datatransfer.StringSelection

/** Recovers a myflow change name from an `openspec/<name>` branch. */
object BranchNameParser {
    private const val PREFIX = "openspec/"

    fun changeName(branch: String?): String? {
        if (branch == null || !branch.startsWith(PREFIX)) return null
        return branch.removePrefix(PREFIX).takeIf { it.isNotBlank() }
    }
}

/**
 * Announces a completed queue once, re-arming only after the queue goes incomplete again so that
 * refreshes cannot repeat the balloon.
 */
class CompletionNotifier(private val project: Project) {

    private var armed = true

    fun onSnapshot(snapshot: QueueSnapshot) {
        val complete = snapshot.items.isNotEmpty() && snapshot.reviewedCount == snapshot.items.size
        if (!complete) {
            armed = true
            return
        }
        if (!armed) return
        armed = false
        notifyComplete(snapshot.items.size)
    }

    private fun notifyComplete(count: Int) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Review Queue")
            .createNotification("All $count files reviewed", NotificationType.INFORMATION)

        changeName()?.let { name ->
            val command = "/myflow-do-done $name"
            notification.addAction(
                NotificationAction.createSimpleExpiring("Copy $command") {
                    CopyPasteManager.getInstance().setContents(StringSelection(command))
                }
            )
        }
        notification.notify(project)
    }

    private fun changeName(): String? =
        GitRepositoryManager.getInstance(project).repositories
            .firstNotNullOfOrNull { BranchNameParser.changeName(it.currentBranch?.name) }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*BranchNameParserTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Wire the notifier into `ReviewQueueService`**

In `ReviewQueueService`, add the field and fire it from `fireChanged()`:

```kotlin
    private val notifier = dev.tweety.reviewqueue.notify.CompletionNotifier(project)
```

and change `fireChanged()` to:

```kotlin
    private fun fireChanged() {
        notifier.onSnapshot(snapshot())
        listeners.toList().forEach { it() }
    }
```

- [ ] **Step 6: Verify the notification and clipboard handoff**

Run: `./gradlew runIde`

Open a myflow apply worktree on an `openspec/<name>` branch with staged files. Mark every file reviewed.

Expected: a balloon reading `All N files reviewed` with a *Copy `/myflow-do-done <name>`* action. Click it, then paste somewhere.

Expected: the clipboard contains `/myflow-do-done <name>`.

Then press Refresh several times.

Expected: no repeated balloons. Unmark one file and re-mark it.

Expected: exactly one new balloon.

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew test`
Expected: PASS, every test from Tasks 2–12.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: notify on completed review with myflow command handoff"
```

---

### Task 13: End-to-end verification against a real Gate B review

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: everything.
- Produces: an installable plugin zip and a README describing installation and use.

- [ ] **Step 1: Build the distributable plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL, and `build/distributions/review-queue-0.1.0.zip` exists.

- [ ] **Step 2: Verify the plugin against the platform's own checks**

Run: `./gradlew verifyPlugin`
Expected: BUILD SUCCESSFUL with no compatibility errors for build 262. Fix anything it reports before continuing.

- [ ] **Step 3: Install into the real IDE and run one full Gate B pass**

```bash
open -na "IntelliJ IDEA"
```

Install `build/distributions/review-queue-0.1.0.zip` via Settings → Plugins → gear icon → Install Plugin from Disk, and restart when prompted.

Then open a real myflow apply worktree that is sitting at `awaiting-do-review`:

```bash
git -C /Users/tweety53/Projects/gymie worktree list
```

Review the whole staged diff through the tool window, marking each file.

Expected: every staged file across every attached git root is listed and grouped by root; marking walks the queue to completion; the completion balloon copies the right `/myflow-do-done` command.

- [ ] **Step 4: Write `README.md`**

```markdown
# Review Queue

An IntelliJ IDEA plugin for working through a diff file by file, marking each one reviewed.

Built for the myflow Gate B manual review, where a change sits staged and uncommitted in a worktree
and needs a human read-through before `/myflow-do-done`.

## Install

Download or build the plugin zip, then Settings → Plugins → gear → Install Plugin from Disk.

```bash
./gradlew buildPlugin   # build/distributions/review-queue-<version>.zip
```

## Use

Open the **Review Queue** tool window on the right.

- **Scope** — choose Staged (the default), Branch vs Base, or an explicit Commit Range.
- **Mark Reviewed** — marks the selected file and moves to the next unreviewed one.
- Click any row to open its diff; reviewed files stay listed so they can be revisited.
- **Refresh** re-reads the scope. **Reset All** clears every mark in the project.

Reviewed marks are content-addressed: editing a file drops its mark automatically, so a fix round
returns exactly the rewritten files to the queue. Marks are stored in per-project workspace state —
never in the repository, and never shared between machines.

## Develop

```bash
./gradlew test       # unit + integration tests
./gradlew runIde     # sandbox IDE with the plugin loaded
./gradlew verifyPlugin
```

## Requirements

IntelliJ IDEA 2026.2 or newer (build 262+), with the bundled Git plugin enabled.
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: add README and verify plugin end to end"
```
