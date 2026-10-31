package app.tideline

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/** Android's user-selected date/time formats, including the 12/24-hour override. */
object PhoneFormats {
    data class ClockParts(val main: String, val suffix: String)
    fun time(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))
    fun dateTime(context: Context, millis: Long): String =
        DateFormat.getDateFormat(context).format(Date(millis)) + " " + time(context, millis)

    fun hour(context: Context, millis: Long, minutes: Boolean): ClockParts {
        val locale = context.resources.configuration.locales[0]
        val is24 = DateFormat.is24HourFormat(context)
        val hour = SimpleDateFormat(if (is24) "H" else "h", locale).format(Date(millis))
        val label = if (minutes) time(context, millis) else
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, if (is24) "H" else "h"), locale).format(Date(millis))
        val padded = SimpleDateFormat(if (is24) "HH" else "hh", locale).format(Date(millis))
        val main = when { label.startsWith(padded) -> padded; label.startsWith(hour) -> hour; else -> label }
        return ClockParts(main, label.removePrefix(main))
    }
}
