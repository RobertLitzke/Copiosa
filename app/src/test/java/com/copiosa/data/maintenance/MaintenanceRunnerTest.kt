package com.copiosa.data.maintenance

import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.model.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceRunnerTest {

    private val now = 1_700_000_000_000L

    // --- Fakes -------------------------------------------------------------

    private class FakeTask(
        override val id: String,
        override val scope: TaskScope = TaskScope.ALL_PROFILES,
        override val needsLlm: Boolean = false,
        override val minIntervalMs: Long = 0,
        private val fail: Boolean = false,
    ) : MaintenanceTask {
        val ranFor = mutableListOf<String>()

        override suspend fun run(ctx: TaskContext) {
            ranFor += ctx.profileTag
            if (fail) error("boom")
        }
    }

    private class FakeLastRunStore(initial: Map<String, Long> = emptyMap()) : LastRunStore {
        val stamps = initial.toMutableMap()

        override suspend fun get(taskId: String, tag: String) = stamps["$taskId/$tag"] ?: 0L
        override suspend fun set(taskId: String, tag: String, at: Long) {
            stamps["$taskId/$tag"] = at
        }
    }

    private class FakeSettings(
        private val active: String = "es",
        private val tags: List<String> = listOf("es"),
        private val backgroundLlmTasks: Boolean = true,
        private val hasKey: Boolean = true,
    ) : ProfileSettingsSource {
        override suspend fun activeTag() = active
        override suspend fun allTags() = tags
        override suspend fun settingsFor(tag: String) =
            AppSettings(targetLanguage = tag, backgroundLlmTasks = backgroundLlmTasks)

        override fun hasApiKey(provider: ProviderId) = hasKey
    }

    private fun runner(
        tasks: List<MaintenanceTask>,
        settings: ProfileSettingsSource = FakeSettings(),
        store: LastRunStore = FakeLastRunStore(),
    ) = MaintenanceRunner(MaintenanceTasks.of(tasks), settings, store)

    // --- Tests -------------------------------------------------------------

    @Test
    fun `runs every task for every profile`() = runTest {
        val task = FakeTask("a")
        runner(listOf(task), FakeSettings(active = "es", tags = listOf("es", "fr"))).runAll(now)

        assertEquals(listOf("es", "fr"), task.ranFor)
    }

    @Test
    fun `active-profile tasks skip other profiles`() = runTest {
        val task = FakeTask("a", scope = TaskScope.ACTIVE_PROFILE)
        runner(listOf(task), FakeSettings(active = "fr", tags = listOf("es", "fr"))).runAll(now)

        assertEquals(listOf("fr"), task.ranFor)
    }

    @Test
    fun `llm tasks are skipped when the profile has background tasks off`() = runTest {
        val local = FakeTask("local", needsLlm = false)
        val remote = FakeTask("remote", needsLlm = true)
        runner(listOf(local, remote), FakeSettings(backgroundLlmTasks = false)).runAll(now)

        assertEquals(listOf("es"), local.ranFor)
        assertTrue(remote.ranFor.isEmpty())
    }

    @Test
    fun `llm tasks are skipped when there is no api key, even if the toggle is on`() = runTest {
        val remote = FakeTask("remote", needsLlm = true)
        runner(listOf(remote), FakeSettings(backgroundLlmTasks = true, hasKey = false)).runAll(now)

        assertTrue(remote.ranFor.isEmpty())
    }

    @Test
    fun `a task that ran inside its interval is skipped`() = runTest {
        val task = FakeTask("a", minIntervalMs = 60_000)
        val store = FakeLastRunStore(mapOf("a/es" to now - 59_000))
        runner(listOf(task), store = store).runAll(now)

        assertTrue(task.ranFor.isEmpty())
    }

    @Test
    fun `a task whose interval has elapsed runs again`() = runTest {
        val task = FakeTask("a", minIntervalMs = 60_000)
        val store = FakeLastRunStore(mapOf("a/es" to now - 60_000))
        runner(listOf(task), store = store).runAll(now)

        assertEquals(listOf("es"), task.ranFor)
    }

    @Test
    fun `a throwing task does not abort the tasks after it`() = runTest {
        val broken = FakeTask("broken", fail = true)
        val after = FakeTask("after")
        runner(listOf(broken, after)).runAll(now)

        assertEquals(listOf("es"), after.ranFor)
    }

    @Test
    fun `a throwing task is still stamped, so it backs off instead of retrying every kick`() = runTest {
        val broken = FakeTask("broken", fail = true, minIntervalMs = 60_000)
        val store = FakeLastRunStore()
        val runner = runner(listOf(broken), store = store)

        runner.runAll(now)
        assertEquals(now, store.stamps["broken/es"])

        // A second kick moments later must not re-run it.
        runner.runAll(now + 1_000)
        assertEquals(1, broken.ranFor.size)
    }

    @Test
    fun `a failure for one profile does not stop the next profile`() = runTest {
        val broken = FakeTask("broken", fail = true)
        runner(listOf(broken), FakeSettings(tags = listOf("es", "fr"))).runAll(now)

        assertEquals(listOf("es", "fr"), broken.ranFor)
    }
}
