package com.whitedns.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class WhiteDnsTileService : TileService() {
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Actions.STATE_CHANGED) return
            val state = VpnState.fromWireName(
                intent.getStringExtra(Actions.EXTRA_STATE),
                intent.getStringExtra(Actions.EXTRA_ERROR),
            )
            updateTile(state)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile(VpnRuntimeStateStore.read(this))
        registerStateReceiver()
    }

    override fun onStopListening() {
        unregisterStateReceiver()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (VpnRuntimeStateStore.readAlwaysOn(this)) {
            openMainActivity()
            return
        }
        when (VpnRuntimeStateStore.read(this)) {
            VpnState.Starting,
            VpnState.Started,
            -> startVpnService(Actions.DISCONNECT)
            VpnState.Stopping -> Unit
            VpnState.Stopped,
            VpnState.DailyLimitReached,
            is VpnState.Error,
            -> connectOrOpenApp()
        }
    }

    private fun connectOrOpenApp() {
        if (!PrivacyPolicyAcceptanceStore(this).isAccepted()) {
            openMainActivity()
            return
        }
        if (VpnService.prepare(this) == null) {
            startVpnService(Actions.CONNECT)
            updateTile(VpnState.Starting)
            return
        }
        openMainActivity()
    }

    private fun startVpnService(action: String) {
        val intent = Intent(this, WhiteDnsVpnService::class.java)
            .setAction(action)
            .putExtra(Actions.EXTRA_APP_INITIATED, true)
        if (action == Actions.CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                10,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(Actions.STATE_CHANGED)
        // NOT_EXPORTED on every API level so no other app can spoof the tile's connected state.
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(stateReceiver) }
        receiverRegistered = false
    }

    private fun updateTile(state: VpnState) {
        val tile = qsTile ?: return
        val presentation = QuickSettingsTileStateMapper.presentationFor(state)
        tile.label = getString(presentation.labelRes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(presentation.subtitleRes)
        }
        tile.state = when (presentation.visualState) {
            QuickSettingsTileVisualState.Active -> Tile.STATE_ACTIVE
            QuickSettingsTileVisualState.Inactive -> Tile.STATE_INACTIVE
            QuickSettingsTileVisualState.Unavailable -> Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }

    companion object {
        fun requestTileRefresh(context: Context) {
            TileService.requestListeningState(
                context,
                ComponentName(context, WhiteDnsTileService::class.java),
            )
        }
    }
}
