package com.worktime.trace.geo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.worktime.trace.ui.home.HomeViewModel

/**
 * 定位源抽象：统一“高德定位（已配 Key）”与“系统 LocationManager（无 Key 兜底）”。
 *
 * 坐标系说明：两种来源各自自洽——高德为 GCJ02，系统为 WGS84；
 * 工作地点与实时定位始终来自同一定位源，围栏判定不受偏移影响。
 * 若先以系统定位设点、后配置了高德 Key，建议重新选点。
 */
interface Locator {
    /** 周期定位。回调可能在任意线程。需在主线程调用。 */
    fun startPeriodic(
        intervalMs: Long,
        onLocation: (lat: Double, lng: Double) -> Unit
    )

    /** 单次定位（先回传缓存位置，拿到新位置后再次回传）。需在主线程调用。 */
    fun locateOnce(onLocation: (lat: Double, lng: Double) -> Unit)

    fun destroy()
}

object LocatorFactory {
    /** 已配置高德 Key 用高德定位（更准），否则退回系统定位（无需 Key，开箱即用）。 */
    fun create(context: Context): Locator {
        val appContext = context.applicationContext
        val keyOk = HomeViewModel.isAmapKeyConfigured(appContext as android.app.Application)
        return if (keyOk) AMapLocator(appContext) else SystemLocator(appContext)
    }
}

/** 高德定位实现；Key 鉴权失败时自动降级到系统定位，保证链路可用。 */
class AMapLocator(private val appContext: Context) : Locator {

    private var client: AMapLocationClient? = null
    private var onLocation: ((Double, Double) -> Unit)? = null
    private var currentIntervalMs: Long = 0

    /** 鉴权失败后的系统定位兜底。 */
    private var fallback: SystemLocator? = null

    private val listener = AMapLocationListener { loc ->
        if (loc != null && loc.errorCode == 0) {
            onLocation?.invoke(loc.latitude, loc.longitude)
        } else {
            android.util.Log.w(
                "WorkTimeTrace",
                "AMap location failed: code=${loc?.errorCode} detail=${loc?.locationDetail}"
            )
            // code=7: Key 鉴权失败 → 自动降级系统定位
            if (loc?.errorCode == 7) switchToFallback()
        }
    }

    private fun ensureClient(): AMapLocationClient {
        if (client == null) {
            // 高德隐私合规已在 Application 初始化时调用
            client = AMapLocationClient(appContext).also {
                it.setLocationListener(listener)
            }
        }
        return client!!
    }

    private fun switchToFallback() {
        if (fallback != null) return
        android.util.Log.w("WorkTimeTrace", "AMap auth failed, falling back to system locator")
        runCatching { client?.stopLocation() }
        fallback = SystemLocator(appContext).also {
            val cb = onLocation
            val interval = currentIntervalMs
            if (cb != null && interval > 0) it.startPeriodic(interval, cb)
            else if (cb != null) it.locateOnce(cb)
        }
    }

    override fun startPeriodic(intervalMs: Long, onLocation: (Double, Double) -> Unit) {
        this.onLocation = onLocation
        this.currentIntervalMs = intervalMs
        fallback?.destroy()
        fallback = null
        val option = AMapLocationClientOption().apply {
            setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
            setInterval(intervalMs)
            setOnceLocation(false)
            setNeedAddress(false)
            setMockEnable(false)
            setHttpTimeOut(15000)
        }
        ensureClient().setLocationOption(option)
        ensureClient().startLocation()
    }

    override fun locateOnce(onLocation: (Double, Double) -> Unit) {
        this.onLocation = onLocation
        fallback?.destroy()
        fallback = null
        val option = AMapLocationClientOption().apply {
            setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
            setOnceLocation(true)
            setNeedAddress(false)
            setMockEnable(false)
        }
        ensureClient().setLocationOption(option)
        ensureClient().startLocation()
    }

    override fun destroy() {
        client?.let {
            runCatching {
                it.stopLocation()
                it.onDestroy()
            }
        }
        client = null
        fallback?.destroy()
        fallback = null
    }
}

/** 系统 LocationManager 实现（无需任何 Key / GMS）。 */
class SystemLocator(private val appContext: Context) : Locator {

    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var onLocation: ((Double, Double) -> Unit)? = null

    /** 最近一次被接受的位置（锚点），用于过滤网络定位的跨城漂移。 */
    private var anchor: android.location.Location? = null

    private val listener = LocationListener { loc -> offer(loc) }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 位置可信度过滤：
     * - 超过 10 分钟的陈旧缓存直接丢弃（被动源常广播历史位置）；
     * - GPS/融合位置直接接受；
     * - 网络/被动位置若与锚点相距超过 50km 视为运营商 IP 级错误定位，丢弃。
     */
    private fun offer(loc: android.location.Location) {
        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
        val ageMs = (nowNs - loc.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs > 10 * 60_000L || ageMs < -60_000L) return
        val p = loc.provider
        val direct = p == LocationManager.GPS_PROVIDER || p == LocationManager.FUSED_PROVIDER
        if (!direct) {
            val a = anchor
            if (a != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    a.latitude, a.longitude, loc.latitude, loc.longitude, results
                )
                if (results[0] > 50_000f) {
                    android.util.Log.w(
                        "WorkTimeTrace",
                        "drop wild fix: ${loc.provider} (${loc.latitude},${loc.longitude}) " +
                            "dist=${results[0].toInt()}m"
                    )
                    return
                }
            }
        }
        anchor = loc
        android.util.Log.d(
            "WorkTimeTrace",
            "system fix: ${loc.provider} (${loc.latitude},${loc.longitude}) acc=${loc.accuracy.toInt()}m"
        )
        onLocation?.invoke(loc.latitude, loc.longitude)
    }

    private fun request(intervalMs: Long) {
        if (!granted()) {
            android.util.Log.w("WorkTimeTrace", "SystemLocator: no location permission")
            return
        }
        val minTime = intervalMs.coerceAtMost(30_000L)
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (p in providers) {
            val enabled = runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
            if (enabled) {
                runCatching {
                    lm.requestLocationUpdates(p, minTime, 0f, listener, Looper.getMainLooper())
                }.onFailure {
                    android.util.Log.w("WorkTimeTrace", "SystemLocator: $p failed: $it")
                }
            }
        }
    }

    /** GPS 优先的最近缓存位置；网络缓存仅在有 GPS 锚点附近时采用。 */
    private fun bestLastKnown(): android.location.Location? {
        if (!granted()) return null
        val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        if (gps != null) {
            anchor = gps
            return gps
        }
        return runCatching {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()
    }

    override fun startPeriodic(intervalMs: Long, onLocation: (Double, Double) -> Unit) {
        this.onLocation = onLocation
        request(intervalMs)
        // 立即回传一次缓存位置，加快首次围栏判定
        bestLastKnown()?.let { offer(it) }
    }

    override fun locateOnce(onLocation: (Double, Double) -> Unit) {
        this.onLocation = onLocation
        bestLastKnown()?.let { offer(it) }
        request(1000L)
    }

    override fun destroy() {
        runCatching { lm.removeUpdates(listener) }
        onLocation = null
    }
}
