package com.screentrack.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentrack.app.data.model.AppUsageInfo
import com.screentrack.app.data.model.DailyAnalyticsPoint
import com.screentrack.app.data.repository.UsageStatsRepository
import com.screentrack.app.ui.theme.AccentEmerald
import com.screentrack.app.ui.theme.AccentRose
import kotlin.math.abs

/**
 * Lightweight 7-Day History Screen.
 * Shows the rolling 7-day bar chart, daily average, comparison with yesterday,
 * and interactive day-by-day app breakdown. Data older than 7 days is automatically cleaned up.
 */
@Composable
fun AnalyticsScreen() {
    val context = LocalContext.current
    val usageRepo = remember { UsageStatsRepository(context) }

    var weeklyPoints by remember { mutableStateOf<List<DailyAnalyticsPoint>>(emptyList()) }
    var selectedDay by remember { mutableStateOf<DailyAnalyticsPoint?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        val points = usageRepo.getWeeklyAnalytics()
        weeklyPoints = points
        selectedDay = points.lastOrNull()
        isLoading = false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "7-Day Screen-Time History",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Tap any day in the past 7 days to inspect exact app usage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        // Auto-Cleanup Notice Badge
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AccentEmerald.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoDelete,
                        contentDescription = null,
                        tint = AccentEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Lightweight Rolling 7-Day Window: History older than 7 days is automatically cleaned up.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }

        if (isLoading) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading 7-day history...")
                }
            }
        } else {
            // 7-Day Bar Chart Card
            item {
                val sevenDayTotal = weeklyPoints.sumOf { it.totalScreenTimeMs }
                val sevenDayAvg = if (weeklyPoints.isNotEmpty()) sevenDayTotal / weeklyPoints.size else 0L

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Last 7 Days",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "7-Day Total: ${AppUsageInfo.formatDuration(sevenDayTotal)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Text(
                                text = "Daily Avg: ${AppUsageInfo.formatDuration(sevenDayAvg)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(22.dp))

                        val maxDuration = (weeklyPoints.maxOfOrNull { it.totalScreenTimeMs } ?: 1L).coerceAtLeast(1L)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            weeklyPoints.forEach { point ->
                                val rawRatio = point.totalScreenTimeMs.toFloat() / maxDuration.toFloat()
                                val heightFraction = if (rawRatio.isNaN() || rawRatio.isInfinite()) 0.05f else rawRatio.coerceIn(0.05f, 1f)
                                val isSelected = selectedDay?.date == point.date

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedDay = point }
                                ) {
                                    Text(
                                        text = AppUsageInfo.formatDuration(point.totalScreenTimeMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight(heightFraction)
                                            .fillMaxWidth(0.55f)
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                            )
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = point.dayOfWeek,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Comparison vs Yesterday
            item {
                val todayMs = weeklyPoints.lastOrNull()?.totalScreenTimeMs ?: 0L
                val yesterdayMs = if (weeklyPoints.size >= 2) weeklyPoints[weeklyPoints.size - 2].totalScreenTimeMs else 0L
                val diffMs = todayMs - yesterdayMs
                val isMore = diffMs >= 0
                val diffPercent = if (yesterdayMs > 0) ((diffMs.toDouble() / yesterdayMs.toDouble()) * 100).toInt() else 0

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    if (isMore) AccentRose.copy(alpha = 0.15f) else AccentEmerald.copy(alpha = 0.15f),
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isMore) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                contentDescription = null,
                                tint = if (isMore) AccentRose else AccentEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = "Today vs. Yesterday",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (yesterdayMs == 0L) "No usage recorded yesterday"
                                else "${if (isMore) "+" else ""}${diffPercent}% (${AppUsageInfo.formatDuration(abs(diffMs))}) vs yesterday",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isMore) AccentRose else AccentEmerald
                            )
                        }
                    }
                }
            }

            // Selected Day App Breakdown
            val activeDay = selectedDay ?: weeklyPoints.lastOrNull()
            if (activeDay != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${activeDay.dayOfWeek} (${activeDay.date}) Apps",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Total: ${activeDay.formattedDuration}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (activeDay.apps.isEmpty()) {
                    item {
                        Text(
                            text = "No app activity recorded on ${activeDay.date}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    items(activeDay.apps, key = { "${activeDay.date}_${it.packageName}" }) { app ->
                        AppUsageRowItem(app = app)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
