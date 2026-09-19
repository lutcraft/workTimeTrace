package com.worktime.trace

import android.app.Application
import android.content.pm.PackageManager
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.worktime.trace.data.AttendanceRepository
import com.worktime.trace.data.prefs.SettingsStore

class WorkTimeTraceApp : Application() {

    lateinit var repository: AttendanceRepository
        private set

    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        // 高德合规接口：必须在任何地图/定位功能初始化之前调用，
        // 否则 SDK 拒绝工作（地图白屏、定位无回调）。
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        // 动态设置 Key：部分机型/时序下 SDK 读不到 Manifest meta-data（鉴权请求 key 为空），
        // 官方排查文档建议代码侧显式设置，与 meta-data 双保险。
        amapKey()?.let { key ->
            MapsInitializer.setApiKey(key)
            AMapLocationClient.setApiKey(key)
        }

        repository = AttendanceRepository(this)
        settingsStore = SettingsStore(this)
    }

    /** Manifest 中配置的高德 Key；未配置或占位符返回 null。 */
    fun amapKey(): String? = try {
        val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val key = info.metaData?.getString("com.amap.api.v2.apiconfig")
        if (key != null && key.isNotBlank() && key != "YOUR_AMAP_ANDROID_KEY") key else null
    } catch (_: Exception) {
        null
    }
}
