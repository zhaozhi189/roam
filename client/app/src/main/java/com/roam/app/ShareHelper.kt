package com.roam.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share Intent 工具:把 internal storage 的视频文件通过 FileProvider 暴露给系统分享面板,
 * 用户在面板里选「微信」即可发送(M1 第 0 周 H8 验证用)。
 *
 * 不集成微信 OpenSDK,理由见 ADR-005:个人开发者无企业资质。
 *
 * TODO M1 真机调试:
 *   - 实测微信对 video/mp4 的 EXTRA_STREAM 是否需要额外 grant flag
 *   - 实测分享后微信是否过度压缩(对比原片画质)
 *   - 如果微信不在分享面板里,排查 FileProvider authority / file_paths.xml 配置
 */
object ShareHelper {

    private const val TAG = "ShareHelper"

    /**
     * 触发系统分享面板分享视频
     *
     * @param context  Activity context(需要能 startActivity)
     * @param filePath internal storage 内的绝对路径(/data/data/com.roam.app/files/recordings/xxx.mp4)
     * @param chooserTitle 分享面板顶部标题
     * @return 是否成功触发(false 表示文件不存在或权限问题)
     */
    fun shareVideo(
        context: Context,
        filePath: String,
        chooserTitle: String = "分享漫游视频"
    ): Boolean {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            Log.e(TAG, "File not found: $filePath")
            return false
        }

        val authority = "${context.packageName}.fileprovider"
        val uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: IllegalArgumentException) {
            // 通常是 file_paths.xml 没覆盖到该路径
            Log.e(TAG, "FileProvider.getUriForFile failed for $filePath", e)
            return false
        }

        // 按后缀决定 mime(RoamLogic 单一来源,RoamLogicTest 覆盖)
        val mime = RoamLogic.mimeFromFilename(filePath)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // 关键:分享给的 App(微信等)需要读权限
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            // Activity 之外的 context 启动 Activity 需要 NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(chooser)
            Log.d(TAG, "Share chooser launched for $filePath uri=$uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startActivity failed", e)
            false
        }
    }
}
