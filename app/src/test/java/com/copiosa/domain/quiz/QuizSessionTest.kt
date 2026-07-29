package com.copiosa.domain.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizSessionTest {

    private fun readyCue(id: Long, type: CueType, value: CueValue) = ReadyCue(
        id = id,
        type = type,
        valueKey = value.key(),
        displayText = CueGenerators.forType(type).displayText(value),
        filePath = "/pool/$id.wav",
        durationMs = 1500,
    )

    private val config = QuizPoolConfig(langCode = "es")

    @Test
    fun `build draws count questions with valid options`() {
        val cues = (1..40).map { readyCue(it.toLong(), CueType.NUMBER, CueValue.Number(it * 2)) }
        val questions = QuizSessionBuilder(Random(5)).build(cues, config, count = 20)
        assertEquals(20, questions.size)
        questions.forEach { q ->
            assertEquals(q.options.size, q.options.distinct().size)
            assertTrue(q.options.size in 2..4)
            assertEquals(q.displayAnswer, q.options[q.answerIndex])
        }
    }

    @Test
    fun `mixed pools keep each question's own cue type`() {
        val cues = (1..10).map { readyCue(it.toLong(), CueType.NUMBER, CueValue.Number(it + 10)) } +
            (11..20).map { readyCue(it.toLong(), CueType.TIME, CueValue.Time(it - 10, 15)) }
        val questions = QuizSessionBuilder(Random(2)).build(cues, config, count = 20)
        assertEquals(20, questions.size)
        questions.filter { it.cueType == CueType.TIME }.forEach { assertTrue(it.displayAnswer.contains(':')) }
        questions.filter { it.cueType == CueType.NUMBER }.forEach { assertTrue(!it.displayAnswer.contains(':')) }
    }

    @Test
    fun `number options are sorted ascending`() {
        val cues = (1..20).map { readyCue(it.toLong(), CueType.NUMBER, CueValue.Number(it * 7)) }
        val questions = QuizSessionBuilder(Random(3)).build(cues, config, count = 20)
        questions.forEach { q ->
            val nums = q.options.map { it.toInt() }
            assertEquals(nums.sorted(), nums)
        }
    }

    @Test
    fun `time options are sorted chronologically`() {
        val cues = (1..12).map { readyCue(it.toLong(), CueType.TIME, CueValue.Time(it, (it % 4) * 15)) }
        val questions = QuizSessionBuilder(Random(4)).build(cues, config, count = 12)
        questions.forEach { q ->
            val minutes = q.options.map { opt -> opt.split(':').let { it[0].toInt() * 60 + it[1].toInt() } }
            assertEquals(minutes.sorted(), minutes)
        }
    }

    @Test
    fun `answer slot is spread across positions, not stuck in the middle`() {
        val wide = QuizPoolConfig(langCode = "es", numberMax = 1_000)
        // Mid-range values so distractors exist on both sides of every answer.
        val numberCues = (1..200).map { readyCue(it.toLong(), CueType.NUMBER, CueValue.Number(100 + it * 2)) }
        val numberQs = QuizSessionBuilder(Random(11)).build(numberCues, wide, count = 200)
        val numberSlots = IntArray(4).also { c -> numberQs.forEach { c[it.answerIndex]++ } }
        (0..3).forEach { assertTrue("number slot $it empty: ${numberSlots.toList()}", numberSlots[it] > 0) }
        // The reported bug was slot 0 being starved; it should now get a fair share (>1/8).
        assertTrue("slot 0 starved: ${numberSlots.toList()}", numberSlots[0] >= numberQs.size / 8)

        // Times share the mechanism — mid-clock answers should spread too.
        val timeCues = (0..199).map { readyCue(500 + it.toLong(), CueType.TIME, CueValue.Time(2 + it % 9, (it % 12) * 5)) }
        val timeQs = QuizSessionBuilder(Random(12)).build(timeCues, config, count = 200)
        val timeSlots = IntArray(4).also { c -> timeQs.forEach { c[it.answerIndex]++ } }
        (0..3).forEach { assertTrue("time slot $it empty: ${timeSlots.toList()}", timeSlots[it] > 0) }
    }

    @Test
    fun `repeat sessions vary the option sets`() {
        val cues = (1..30).map { readyCue(it.toLong(), CueType.NUMBER, CueValue.Number(it + 20)) }
        val a = QuizSessionBuilder(Random(1)).build(cues, config, count = 30)
        val b = QuizSessionBuilder(Random(2)).build(cues, config, count = 30)
        val optionsByCue = { qs: List<QuizQuestion> -> qs.associate { it.cueId to it.options.toSet() } }
        // At least some cues should see a different distractor set across sessions.
        val overlap = optionsByCue(a).entries.count { (id, opts) -> optionsByCue(b)[id] == opts }
        assertTrue(overlap < 30)
    }

    // --- phrase cues (stored options) ---

    private fun phraseCue(id: Long, correct: String, distractors: List<String>) = ReadyCue(
        id = id,
        type = CueType.RESTAURANT,
        valueKey = "phrase$id",
        displayText = correct,
        filePath = "/pool/$id.wav",
        durationMs = 1500,
        options = PhraseOptions(correct, distractors),
    )

    @Test
    fun `phrase questions use stored options with the correct answer marked`() {
        val stored = listOf("A wrong one", "B wrong one", "C wrong one", "D wrong one")
        val cues = (1..20).map { phraseCue(it.toLong(), "Ready to order?", stored) }
        val questions = QuizSessionBuilder(Random(7)).build(cues, QuizPoolConfig(langCode = "es"), count = 20)
        assertEquals(20, questions.size)
        questions.forEach { q ->
            assertEquals(4, q.options.size)
            assertEquals(q.options.size, q.options.distinct().size)
            // The correct answer is present and answerIndex points to it.
            assertEquals("Ready to order?", q.options[q.answerIndex])
            assertEquals(q.displayAnswer, q.options[q.answerIndex])
            // The three shown distractors are a subset of the four stored ones.
            val shown = q.options.filterIndexed { i, _ -> i != q.answerIndex }
            assertTrue("unexpected distractor in $shown", stored.containsAll(shown))
            assertEquals(3, shown.size)
        }
    }

    @Test
    fun `phrase options are not forced into sorted order`() {
        // Alphabetically-labelled distractors so a stable sort would be detectable; across many cues
        // the correct answer must land in varied positions rather than always first/last.
        val stored = listOf("alpha", "bravo", "charlie", "delta")
        val cues = (1..200).map { phraseCue(it.toLong(), "zulu correct", stored) }
        val questions = QuizSessionBuilder(Random(9)).build(cues, QuizPoolConfig(langCode = "es"), count = 200)
        val slots = questions.map { it.answerIndex }.toSet()
        assertTrue("correct answer stuck in one slot: $slots", slots.size > 1)
    }

    @Test
    fun `phrase cue lacking enough distractors is skipped`() {
        val cues = listOf(phraseCue(1, "Only two", listOf("one", "two")))
        val questions = QuizSessionBuilder(Random(1)).build(cues, QuizPoolConfig(langCode = "es"), count = 1)
        assertTrue(questions.isEmpty())
    }

    // --- scoring ---

    @Test
    fun `points decay monotonically with latency`() {
        val fast = QuizScoring.points(correct = true, latencyMs = 200, streak = 0)
        val mid = QuizScoring.points(correct = true, latencyMs = 2000, streak = 0)
        val slow = QuizScoring.points(correct = true, latencyMs = 8000, streak = 0)
        assertTrue(fast > mid)
        assertTrue(mid > slow)
    }

    @Test
    fun `slow but correct hits the floor, wrong scores zero`() {
        assertEquals(100, QuizScoring.points(correct = true, latencyMs = 60_000, streak = 0))
        assertEquals(0, QuizScoring.points(correct = false, latencyMs = 200, streak = 4))
    }

    @Test
    fun `points scale with listening speed`() {
        val slow = QuizScoring.points(correct = true, latencyMs = 1000, streak = 0, speed = 0.75f)
        val normal = QuizScoring.points(correct = true, latencyMs = 1000, streak = 0, speed = 1.0f)
        val fast = QuizScoring.points(correct = true, latencyMs = 1000, streak = 0, speed = 1.5f)
        assertTrue(slow < normal)
        assertTrue(fast > normal)
        // A wrong answer scores zero regardless of speed.
        assertEquals(0, QuizScoring.points(correct = false, latencyMs = 1000, streak = 0, speed = 1.5f))
    }

    @Test
    fun `streak multiplier caps at five`() {
        assertEquals(QuizScoring.streakMultiplier(5), QuizScoring.streakMultiplier(12), 1e-9)
        assertEquals(1.0, QuizScoring.streakMultiplier(0), 1e-9)
        val base = QuizScoring.points(correct = true, latencyMs = 60_000, streak = 0)
        val streaky = QuizScoring.points(correct = true, latencyMs = 60_000, streak = 5)
        assertEquals((base * 1.5).toInt(), streaky)
    }

    @Test
    fun `session stats aggregate answers`() {
        val q = QuizQuestion(1, CueType.NUMBER, "/a.wav", 1000, "13", listOf("13", "30"), 0)
        val answers = listOf(
            QuizAnswer(q, selectedIndex = 0, latencyMs = 1000, points = 700),
            QuizAnswer(q, selectedIndex = 0, latencyMs = 3000, points = 400),
            QuizAnswer(q, selectedIndex = 1, latencyMs = 2000, points = 0),
        )
        val stats = SessionStats.from(answers)
        assertEquals(3, stats.answered)
        assertEquals(2, stats.correct)
        assertEquals(2000, stats.avgLatencyMs)
        assertEquals(2, stats.bestStreak)
        assertEquals(1100, stats.score)
    }
}
