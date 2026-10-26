package io.github.burakgon.stop.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.burakgon.stop.R
import io.github.burakgon.stop.model.StopReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Calendar
import java.util.Date

@Composable
fun durationLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.grace_immediately)
    seconds < 60 -> stringResource(R.string.duration_seconds, seconds)
    seconds < 3_600 -> stringResource(R.string.duration_minutes, seconds / 60)
    else -> stringResource(R.string.duration_hours, seconds / 3_600)
}

/** "0:42" style countdown. */
fun countdownLabel(millis: Long): String {
    val total = ((millis + 999) / 1_000).coerceAtLeast(0)
    return if (total >= 3_600) {
        "%d:%02d:%02d".format(total / 3_600, total % 3_600 / 60, total % 60)
    } else {
        "%d:%02d".format(total / 60, total % 60)
    }
}

@Composable
fun reasonLabel(reason: StopReason): String = stringResource(
    when (reason) {
        StopReason.WOKE -> R.string.reason_woke
        StopReason.LEFT -> R.string.reason_left
        StopReason.RUNNING -> R.string.reason_running
        StopReason.MANUAL -> R.string.reason_manual
    }
)

@Composable
fun relativeTime(time: Long, now: Long): String {
    val elapsed = now - time
    return when {
        elapsed < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.time_just_now)
        elapsed < DateUtils.HOUR_IN_MILLIS -> stringResource(R.string.time_minutes_ago, (elapsed / DateUtils.MINUTE_IN_MILLIS).toInt())
        elapsed < 6 * DateUtils.HOUR_IN_MILLIS -> stringResource(R.string.time_hours_ago, (elapsed / DateUtils.HOUR_IN_MILLIS).toInt())
        else -> dayAndTime(time, now)
    }
}

@Composable
fun timeOfDay(time: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(time))

@Composable
fun dayLabel(dayStart: Long, now: Long): String {
    val today = startOfDay(now)
    return when (dayStart) {
        today -> stringResource(R.string.time_today)
        startOfDay(today - 1) -> stringResource(R.string.time_yesterday)
        else -> DateUtils.formatDateTime(
            LocalContext.current, dayStart,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
        )
    }
}

@Composable
private fun dayAndTime(time: Long, now: Long): String {
    val day = dayLabel(startOfDay(time), now)
    return "$day, ${timeOfDay(time)}"
}

/** Local midnight of the day [time] falls in. */
fun startOfDay(time: Long): Long = Calendar.getInstance().run {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

/** Local midnight that starts the day after [time]. */
fun nextDayStart(time: Long): Long = daysBefore(time, -1)

/** Emits the start of today, and again whenever a new day begins. */
fun dayStarts(): Flow<Long> = flow {
    while (true) {
        val now = System.currentTimeMillis()
        emit(startOfDay(now))
        delay(nextDayStart(now) - now + 1_000)
    }
}

/** Local midnight [days] days before the day of [time]. */
fun daysBefore(time: Long, days: Int): Long = Calendar.getInstance().run {
    timeInMillis = startOfDay(time)
    add(Calendar.DAY_OF_YEAR, -days)
    timeInMillis
}
