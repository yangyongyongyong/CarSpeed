package com.thomas.carspeed

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class TripStatsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_stats)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val stats = TripStatsStore.loadForReading(this)
        bind(R.id.tvTodayDistance, R.id.tvTodaySpeed, R.id.tvTodayDuration, stats.day)
        bind(R.id.tvMonthDistance, R.id.tvMonthSpeed, R.id.tvMonthDuration, stats.month)
        bind(R.id.tvTotalDistance, R.id.tvTotalSpeed, R.id.tvTotalDuration, stats.cumulative)
    }

    private fun bind(distanceId: Int, speedId: Int, durationId: Int, bucket: TripStatsBucket) {
        findViewById<TextView>(distanceId).text = getString(R.string.trip_stats_distance, TripStatsFormat.distance(bucket.distanceMeters))
        findViewById<TextView>(speedId).text = getString(R.string.trip_stats_speed, TripStatsFormat.speed(bucket.averageSpeedKmh()))
        findViewById<TextView>(durationId).text = getString(R.string.trip_stats_duration, TripStatsFormat.duration(bucket.durationMs))
    }
}
