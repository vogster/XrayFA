package com.android.xrayfa.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.android.xrayfa.common.di.qualifier.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级单例：缓存 "已安装且具有 INTERNET 权限的应用" 列表。
 *
 * 设计要点：
 * - 首次调用 [load] 时执行一次完整的 PackageManager 扫描，结果常驻内存。
 * - 通过监听系统包广播 (PACKAGE_ADDED / REMOVED / REPLACED / CHANGED) 与
 *   LOCALE_CHANGED 自动失效缓存，保证数据始终新鲜，不会陈旧或不可用。
 * - 收到广播时：清空缓存；若当前有订阅者（用户正停留在 Apps 页），
 *   则立即重载；否则延迟到下次 [load] 调用时懒加载。
 * - 用 Mutex + double-check 保证并发场景下只扫描一次。
 * - 缓存的数据 [CachedAppInfo] 是不可变元数据，不包含用户的"是否允许"状态，
 *   后者由 ViewModel 与 SettingsRepository.packagesFlow 组合派生。
 */
@Singleton
class AppInfoRepository @Inject constructor(
    @Application private val context: Context,
) {

    data class CachedAppInfo(
        val appName: String,
        val packageName: String,
        val icon: Painter,
    )

    private val _apps = MutableStateFlow<List<CachedAppInfo>?>(null)
    /** null 表示尚未加载完成 */
    val apps: StateFlow<List<CachedAppInfo>?> = _apps.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            Log.i(TAG, "package/locale changed: ${intent?.action} ${intent?.dataString}")
            // 失效缓存。若有订阅者立即重载；否则等下一次 load() 懒加载。
            _apps.value = null
            if (_apps.subscriptionCount.value > 0) {
                scope.launch { load(forceRefresh = true) }
            }
        }
    }

    init {
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            packageReceiver,
            pkgFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Locale 变化没有 data scheme，必须独立注册。
        val localeFilter = IntentFilter(Intent.ACTION_LOCALE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            packageReceiver,
            localeFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * 加载已安装应用列表。
     * - 已加载且未要求强制刷新 → 立即返回（命中缓存）。
     * - 否则在 IO 线程执行扫描。Mutex 保证并发只扫描一次。
     */
    suspend fun load(forceRefresh: Boolean = false) {
        // 快速路径：无锁判断
        if (!forceRefresh && _apps.value != null) return
        mutex.withLock {
            // 双重检查：拿到锁后再判断一次，避免重复扫描
            if (!forceRefresh && _apps.value != null) return
            _loading.value = true
            try {
                val list = withContext(Dispatchers.IO) { fetchFromPackageManager() }
                _apps.value = list
            } finally {
                _loading.value = false
            }
        }
    }

    private fun fetchFromPackageManager(): List<CachedAppInfo> {
        val pm = context.packageManager
        val installedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        return installedPackages.mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null

            val label = runCatching {
                pm.getApplicationLabel(appInfo).toString().trim()
            }.getOrNull() ?: return@mapNotNull null

            val hasInternet = pkgInfo.requestedPermissions
                ?.contains(android.Manifest.permission.INTERNET) == true
            if (!hasInternet || label.isEmpty()) {
                return@mapNotNull null
            }

            val drawable = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                ?: return@mapNotNull null

            CachedAppInfo(
                appName = label,
                packageName = pkgInfo.packageName,
                icon = drawable.toPainter(),
            )
        }.sortedBy { it.appName.lowercase() }
    }

    companion object {
        private const val TAG = "AppInfoRepository"
    }
}

/**
 * 将 Drawable 缩放渲染为 Compose [Painter]。最大边长限制为 [maxSize]dp 等价像素，
 * 避免某些应用提供超大原始图标导致内存暴涨。
 */
internal fun Drawable.toPainter(maxSize: Int = 48): Painter {
    val width = intrinsicWidth.takeIf { it > 0 } ?: 1
    val height = intrinsicHeight.takeIf { it > 0 } ?: 1

    val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height, 1f)

    val bitmapWidth = (width * scale).toInt().coerceAtLeast(1)
    val bitmapHeight = (height * scale).toInt().coerceAtLeast(1)

    val bitmap = createBitmap(bitmapWidth, bitmapHeight)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, bitmapWidth, bitmapHeight)
    draw(canvas)

    return BitmapPainter(bitmap.asImageBitmap())
}
