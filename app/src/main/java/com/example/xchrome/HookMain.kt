package com.example.xchrome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.URLConnection

class HookMain : IXposedHookLoadPackage {

    private val prefs by lazy {
        XSharedPreferences("com.example.xchrome", "settings").apply {
            makeWorldReadable()
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 建议列表已经限制了作用域，这里双重检查
        if (lpparam.packageName != "com.android.chrome") return

        XposedBridge.log("xChrome: 开始 Hook Chrome - ${lpparam.packageName}")

        val startActivityHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 实时生效：每次调用前重新加载配置
                prefs.reload()
                val isEnabled = prefs.getBoolean("disable_jump", false)
                if (!isEnabled) return

                val intent = param.args[0] as? Intent ?: return
                
                // 逻辑：如果 Intent 目标包名不是 Chrome 自己，则拦截
                val targetPackage = intent.`package` ?: intent.component?.packageName
                
                if (targetPackage != null && targetPackage != "com.android.chrome") {
                    XposedBridge.log("xChrome: 拦截到跳转至 App: $targetPackage")
                    param.result = null
                    return
                }

                // 处理隐式跳转 (ACTION_VIEW 且无包名)
                if (intent.action == Intent.ACTION_VIEW && targetPackage == null) {
                    // 这种情况下通常会弹出应用选择器或直接跳转
                    // 我们可以通过 checkIntent 进一步判断，但简单起见直接拦截非 http/https 的跳转
                    val data = intent.dataString ?: ""
                    if (!data.startsWith("http://") && !data.startsWith("https://")) {
                        XposedBridge.log("xChrome: 拦截到协议跳转: $data")
                        param.result = null
                    }
                }
            }
        }

        // Hook startActivity 逻辑保持不变
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivity",
                Intent::class.java,
                startActivityHook
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivityForResult",
                Intent::class.java,
                Int::class.java,
                startActivityHook
            )
        } catch (e: Throwable) {
            XposedBridge.log("xChrome: Hook Activity 失败 - ${e.message}")
        }

        // 新增：Hook 下载完成自动打开
        try {
            val downloadManagerServiceClass = XposedHelpers.findClass(
                "org.chromium.chrome.browser.download.DownloadManagerService",
                lpparam.classLoader
            )
            
            XposedBridge.hookAllMethods(downloadManagerServiceClass, "onDownloadFinished", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    prefs.reload()
                    if (!prefs.getBoolean("auto_open_download", false)) return

                    // 通常参数 0 是 DownloadInfo, 参数 1 是 success (Boolean)
                    val downloadInfo = param.args.firstOrNull { it?.javaClass?.name?.contains("DownloadInfo") == true } ?: return
                    val success = param.args.firstOrNull { it is Boolean } as? Boolean ?: return
                    
                    if (!success) return

                    val filePath = try {
                        XposedHelpers.getObjectField(downloadInfo, "mFilePath") as? String
                    } catch (e: Throwable) {
                        try {
                            XposedHelpers.callMethod(downloadInfo, "getFilePath") as? String
                        } catch (e2: Throwable) {
                            null
                        }
                    }

                    if (filePath.isNullOrEmpty()) return
                    
                    XposedBridge.log("xChrome: 下载完成，准备自动打开: $filePath")
                    
                    val context = try {
                        XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                    } catch (e: Throwable) {
                        null
                    }
                    
                    if (context != null) {
                        openFile(context, filePath)
                    } else {
                        XposedBridge.log("xChrome: 无法从 Service 获取 Context，尝试使用当前 Activity")
                    }
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("xChrome: Hook 下载逻辑失败 (可能 Chrome 版本不兼容) - ${e.message}")
        }

        // 新增：Hook 同名文件直接覆盖
        try {
            val downloadDialogBridgeClass = XposedHelpers.findClass(
                "org.chromium.chrome.browser.download.DownloadDialogBridge",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                downloadDialogBridgeClass,
                "showDialog",
                "org.chromium.ui.base.WindowAndroid",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        prefs.reload()
                        if (!prefs.getBoolean("overwrite_download", false)) return

                        val dialogType = param.args[2] as Int
                        val suggestedPath = param.args[3] as String

                        // dialogType 4: NAME_CONFLICT, 6: DUPLICATE_FILE (视 Chrome 版本而定)
                        if (dialogType == 4 || dialogType == 6) {
                            XposedBridge.log("xChrome: 检测到文件冲突 (type=$dialogType): $suggestedPath")
                            
                            val file = File(suggestedPath)
                            if (file.exists()) {
                                if (file.delete()) {
                                    XposedBridge.log("xChrome: 已删除既存文件，准备覆盖")
                                }
                            }

                            try {
                                val nativePtr = try {
                                    XposedHelpers.getLongField(param.thisObject, "mNativeDownloadDialogBridge")
                                } catch (e: Throwable) {
                                    XposedHelpers.getLongField(param.thisObject, "mNativeDownloadLocationDialogBridge")
                                }
                                
                                XposedHelpers.callMethod(param.thisObject, "nativeOnComplete", nativePtr, suggestedPath)
                                param.result = null
                                XposedBridge.log("xChrome: 已跳过对话框并触发下载")
                            } catch (e: Throwable) {
                                XposedBridge.log("xChrome: 触发覆盖下载失败 - ${e.message}")
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("xChrome: Hook DownloadDialogBridge 失败 - ${e.message}")
        }
    }

    private fun openFile(context: Context, filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                XposedBridge.log("xChrome: 文件不存在: $filePath")
                return
            }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val ext = file.extension.lowercase()
            val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: when(ext) {
                "apk" -> "application/vnd.android.package-archive"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "txt" -> "text/plain"
                else -> "*/*"
            }
            
            val authority = "${context.packageName}.FileProvider"
            val uri = try {
                val fileProviderClass = XposedHelpers.findClass("androidx.core.content.FileProvider", context.classLoader)
                XposedHelpers.callStaticMethod(
                    fileProviderClass,
                    "getUriForFile",
                    context,
                    authority,
                    file
                ) as Uri
            } catch (e: Throwable) {
                XposedBridge.log("xChrome: FileProvider 获取失败，尝试旧版 Uri: ${e.message}")
                Uri.fromFile(file)
            }

            intent.setDataAndType(uri, mimeType)
            context.startActivity(intent)
            XposedBridge.log("xChrome: 成功发起打开请求 [$mimeType]: $filePath")
        } catch (e: Throwable) {
            XposedBridge.log("xChrome: 打开文件失败 - ${e.message}")
        }
    }
}
