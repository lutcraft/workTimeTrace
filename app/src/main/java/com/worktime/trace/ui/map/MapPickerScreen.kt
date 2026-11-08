package com.worktime.trace.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.data.db.WorkplaceEntity
import com.worktime.trace.geo.Locator
import com.worktime.trace.geo.LocatorFactory
import com.worktime.trace.ui.AppVmFactory
import com.worktime.trace.ui.home.HomeViewModel

private val STROKE_COLOR = 0xFF1E6E5C.toInt()
private const val FILL_COLOR = 0x331E6E5C

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    workplaceId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val vm: MapPickerViewModel = viewModel(
        factory = AppVmFactory(context.applicationContext as WorkTimeTraceApp) {
            MapPickerViewModel(it)
        }
    )
    val amapKeyOk = remember { HomeViewModel.isAmapKeyConfigured(context.applicationContext as android.app.Application) }

    var original by remember { mutableStateOf<WorkplaceEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var latLng by remember { mutableStateOf<LatLng?>(null) }
    var radius by remember { mutableStateOf(200f) }
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }

    /** 地图选点/初始加载时统一更新坐标与文本（用户手动输入则只更新坐标，避免重写输入框）。 */
    fun setLatLng(p: LatLng) {
        latLng = p
        latText = String.format("%.6f", p.latitude)
        lngText = String.format("%.6f", p.longitude)
    }

    // ---------- 高德地图 ----------
    val mapView = remember { MapView(context) }
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var circle by remember { mutableStateOf<Circle?>(null) }
    var cameraMoved by remember { mutableStateOf(false) }
    val locationPermissionGranted = remember {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    // 一次性定位（“回到当前位置”，自动适配高德/系统定位源）
    var pickerLocator by remember { mutableStateOf<Locator?>(null) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val moveToCurrent: () -> Unit = {
        try {
            val locator = pickerLocator ?: LocatorFactory.create(context).also {
                pickerLocator = it
            }
            locator.locateOnce { lat, lng, _ ->
                mainHandler.post {
                    val p = LatLng(lat, lng)
                    aMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(p, 16f))
                    if (latLng == null) {
                        // 尚未选点（常见于未配置地图 Key 的白屏场景）：
                        // 直接以当前定位作为工作地点中心
                        setLatLng(p)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // 加载已有地点 / 新建模式自动定位到当前位置（无需用户手动点定位按钮）
    LaunchedEffect(workplaceId) {
        val wp = vm.loadWorkplace(workplaceId)
        if (wp != null) {
            original = wp
            name = wp.name
            radius = wp.radiusMeters
            setLatLng(LatLng(wp.latitude, wp.longitude))
        } else {
            moveToCurrent()
        }
    }

    // MapView 生命周期跟随宿主
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "添加工作地点" else "编辑工作地点") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val p = latLng ?: return@IconButton
                        vm.save(
                            WorkplaceEntity(
                                id = original?.id ?: 0L,
                                name = name.ifBlank { "工作地点" },
                                latitude = p.latitude,
                                longitude = p.longitude,
                                radiusMeters = radius,
                                enabled = original?.enabled ?: true
                            ),
                            onDone = onBack
                        )
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { _ ->
                        mapView
                    },
                    update = { _ ->
                        if (aMap == null) {
                            val map = mapView.map
                            aMap = map
                            if (locationPermissionGranted) {
                                map.myLocationStyle = MyLocationStyle()
                                    .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
                                map.isMyLocationEnabled = true
                            }
                            map.setOnMapClickListener { p ->
                                setLatLng(p)
                            }
                            map.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
                                override fun onMarkerDragStart(m: Marker) {}
                                override fun onMarkerDrag(m: Marker) {}
                                override fun onMarkerDragEnd(m: Marker) {
                                    setLatLng(m.position)
                                }
                            })
                        }
                        val p = latLng
                        if (p != null) {
                            if (marker == null) {
                                marker = aMap?.addMarker(
                                    MarkerOptions()
                                        .position(p)
                                        .draggable(true)
                                        .title("工作地点")
                                        .icon(BitmapDescriptorFactory.defaultMarker(
                                            BitmapDescriptorFactory.HUE_GREEN
                                        ))
                                )
                                circle = aMap?.addCircle(
                                    CircleOptions()
                                        .center(p)
                                        .radius(radius.toDouble())
                                        .strokeWidth(4f)
                                        .strokeColor(STROKE_COLOR)
                                        .fillColor(FILL_COLOR)
                                )
                            } else {
                                marker?.position = p
                                circle?.center = p
                            }
                            circle?.radius = radius.toDouble()
                            if (!cameraMoved) {
                                cameraMoved = true
                                aMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(p, 16f))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                FloatingActionButton(
                    onClick = moveToCurrent,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "回到当前位置")
                }
                if (!amapKeyOk) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Text(
                            "未配置高德地图 Key，地图无法显示。可点击右下角定位按钮获取坐标，" +
                                "或直接在下方手动填写经纬度。",
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---------- 属性设置区 ----------
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { s ->
                            latText = s
                            s.toDoubleOrNull()?.let { lat ->
                                lngText.toDoubleOrNull()?.let { lng ->
                                    latLng = LatLng(lat, lng)
                                }
                            }
                        },
                        label = { Text("纬度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lngText,
                        onValueChange = { s ->
                            lngText = s
                            s.toDoubleOrNull()?.let { lng ->
                                latText.toDoubleOrNull()?.let { lat ->
                                    latLng = LatLng(lat, lng)
                                }
                            }
                        },
                        label = { Text("经度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("打卡范围半径：${radius.toInt()} 米")
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 50f..1000f
                )
                Text(
                    "提示：地图上点击任意位置移动标记，也可直接拖动标记。" +
                        "建议半径不小于 150 米，以抵消定位误差。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val p = latLng ?: return@Button
                        vm.save(
                            WorkplaceEntity(
                                id = original?.id ?: 0L,
                                name = name.ifBlank { "工作地点" },
                                latitude = p.latitude,
                                longitude = p.longitude,
                                radiusMeters = radius,
                                enabled = original?.enabled ?: true
                            ),
                            onDone = onBack
                        )
                    },
                    enabled = latLng != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("保存")
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }

    BackHandler { onBack() }

    DisposableEffect(Unit) {
        onDispose {
            pickerLocator?.let { it.destroy() }
        }
    }
}
