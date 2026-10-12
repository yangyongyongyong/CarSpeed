package com.thomas.carspeed

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class CarSpeedTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile(this)
    }

    override fun onClick() {
        super.onClick()
        if (!OverlayController.canStart(this) && !OverlayController.isRunning(this)) {
            openStatus()
            return
        }
        OverlayController.toggle(this)
        updateTile(this)
        CarSpeedWidgetProvider.updateAll(this)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openStatus() {
        val intent = Intent(this, StatusActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun updateTile(service: TileService) {
            val tile = service.qsTile ?: return
            val running = OverlayController.isRunning(service)
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = service.getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = service.getString(if (running) R.string.tile_running else R.string.tile_idle)
            }
            tile.updateTile()
        }

        fun requestListeningState(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, CarSpeedTileService::class.java)
                )
            }
        }
    }
}
