package com.copiosa.domain.stats

object Streak {
    /**
     * Number of consecutive days with focus activity, counted back from [today].
     *
     * A day with no activity yet *today* does not break the streak — the run may end at [today] or
     * at yesterday. [activeDaysDesc] is distinct epoch days sorted descending (the DAO's order);
     * unsorted or duplicate input is tolerated.
     */
    fun compute(activeDaysDesc: List<Long>, today: Long): Int {
        val days = activeDaysDesc.toSortedSet()
        var cursor = if (today in days) today else today - 1
        var streak = 0
        while (cursor in days) {
            streak++
            cursor--
        }
        return streak
    }

    /**
     * The longest run of consecutive active days ever recorded. [activeDays] is any collection of
     * distinct-or-not epoch days; order doesn't matter. Returns 0 for no activity.
     */
    fun longest(activeDays: Collection<Long>): Int {
        val days = activeDays.toSortedSet()
        var best = 0
        var run = 0
        var prev: Long? = null
        for (day in days) {
            run = if (prev != null && day == prev + 1) run + 1 else 1
            if (run > best) best = run
            prev = day
        }
        return best
    }
}
