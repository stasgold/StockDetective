package com.stocksdetective.app.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stocksdetective.app.MainActivity
import com.stocksdetective.app.R
import com.stocksdetective.app.ai.DetectiveAI
import com.stocksdetective.app.data.HoldingsStore
import com.stocksdetective.app.data.Settings
import com.stocksdetective.app.data.SharedPrefsStore
import com.stocksdetective.kit.CaseBuilder
import com.stocksdetective.kit.CaseFile
import com.stocksdetective.kit.DemoMarketData
import com.stocksdetective.kit.HoldingInput
import com.stocksdetective.kit.InvestigationReport
import com.stocksdetective.kit.MoveCharacter
import com.stocksdetective.kit.MovementFacts
import com.stocksdetective.kit.YahooMarketData
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Keeps investigating while the app is closed: refreshes the portfolio periodically and
 * sends a notification when a holding makes an unusual, stock-specific move.
 */
object BackgroundInvestigator {
    private const val WORK_NAME = "com.stocksdetective.app.refresh"
    const val CHANNEL_ID = "unusual-moves"
    const val EXTRA_SYMBOL = "openCase"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<InvestigationWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.alerts_channel_description)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Unusual, stock-specific moves (market-wide swings and normal noise are skipped). */
    fun shouldAlert(f: MovementFacts, threshold: Double): Boolean =
        abs(f.zScore) >= threshold && f.character != MoveCharacter.MARKET_DRIVEN && f.character != MoveCharacter.NOISE

    /** Key that limits alerts to one per symbol per day. */
    fun alertKey(symbol: String, date: LocalDate = LocalDate.now(ZoneId.systemDefault())): String = "alerted-$symbol-$date"

    /** Cases worth a notification right now; marks each as alerted for today. */
    suspend fun casesToAlert(
        holdings: List<HoldingInput>, builder: CaseBuilder, threshold: Double, settings: Settings,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): List<CaseFile> = holdings.mapNotNull { h ->
        val file = runCatching { builder.buildCase(h) }.getOrNull() ?: return@mapNotNull null
        if (!shouldAlert(file.facts, threshold)) return@mapNotNull null
        if (!settings.markAlerted(alertKey(h.symbol.uppercase(), today))) return@mapNotNull null
        file
    }

    fun notificationTitle(report: InvestigationReport, facts: MovementFacts): String =
        "${report.symbol} ${String.format(Locale.US, "%+.1f%%", facts.dayChange)} · ${report.verdict.label}"

    fun notificationBody(report: InvestigationReport): String = "${report.headline} — tap to open the case."

    fun notify(context: Context, report: InvestigationReport, facts: MovementFacts) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SYMBOL, report.symbol)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, report.symbol.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_detective)
            .setContentTitle(notificationTitle(report, facts))
            .setContentText(notificationBody(report))
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationBody(report)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("case-${report.symbol}".hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}

class InvestigationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = SharedPrefsStore(applicationContext)
        val settings = Settings(store)
        if (!settings.alertsEnabled) return Result.success()
        val holdings = HoldingsStore(store).current.map { it.input }
        if (holdings.isEmpty()) return Result.success()
        val provider = if (settings.demoMode) DemoMarketData() else YahooMarketData()
        val builder = CaseBuilder(provider)
        // On-device AI may refuse background requests; the detective then falls back to rules.
        val ai = DetectiveAI().also { it.refreshStatus() }
        for (file in BackgroundInvestigator.casesToAlert(holdings, builder, settings.alertSensitivity, settings)) {
            val report = ai.investigate(file)
            BackgroundInvestigator.notify(applicationContext, report, file.facts)
        }
        return Result.success()
    }
}
