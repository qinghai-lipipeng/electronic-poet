package com.otaku.poet.data

import android.app.ActivityManager
import android.content.Context
import android.os.Process

/**
 * 内存守护：生成过程中监控应用内存占用，确保不超过设备可用内存的指定比例。
 */
object MemoryGuard {

    /** 生成时检查内存的最小间隔（段）：短文按此间隔检查，长文由调用方稀疏化。 */
    const val CHECK_INTERVAL = 10

    /** 设备当前可用内存（字节）。 */
    fun availableMemory(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    /** 应用内存占用上限（字节）= 可用内存 × percent / 100。 */
    fun limitBytes(context: Context, percent: Int): Long =
        (availableMemory(context) * percent.coerceIn(1, 100) / 100.0).toLong()

    /** 当前应用的 PSS 内存占用（字节），包含 Java 堆与 native 堆。 */
    fun appPssBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pids = intArrayOf(Process.myPid())
        val infos = am.getProcessMemoryInfo(pids)
        return if (infos.isNotEmpty()) infos[0].totalPss * 1024L else 0L
    }

    /** 当前应用内存是否已超过指定百分比上限。 */
    fun isOverLimit(context: Context, percent: Int): Boolean =
        appPssBytes(context) > limitBytes(context, percent)
}
