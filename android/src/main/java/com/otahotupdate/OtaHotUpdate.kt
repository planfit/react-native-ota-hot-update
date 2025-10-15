package com.otahotupdate

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.rnhotupdate.Common.CURRENT_VERSION_CODE
import com.rnhotupdate.Common.DEFAULT_BUNDLE
import com.rnhotupdate.Common.PATH
import com.rnhotupdate.Common.VERSION
import com.rnhotupdate.SharedPrefs
import android.util.Log


class OtaHotUpdate : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return if (name == OtaHotUpdateModule.NAME) {
      OtaHotUpdateModule(reactContext)
    } else {
      null
    }
  }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
    return ReactModuleInfoProvider {
      val moduleInfos: MutableMap<String, ReactModuleInfo> = HashMap()
      val isTurboModule: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
      moduleInfos[OtaHotUpdateModule.NAME] = ReactModuleInfo(
        OtaHotUpdateModule.NAME,
        OtaHotUpdateModule.NAME,
        false,  // canOverrideExistingModule
        false,  // needsEagerInit
        false,  // isCxxModule
        isTurboModule // isTurboModule
      )
      moduleInfos
    }
  }
  companion object {
    fun Context.getVersionCode(): String {
      return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
          packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0)
          ).longVersionCode.toString()
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
          @Suppress("DEPRECATION")
          packageManager.getPackageInfo(packageName, 0).longVersionCode.toString()
        }
        else -> {
          @Suppress("DEPRECATION")
          packageManager.getPackageInfo(packageName, 0).versionCode.toString()
        }
      }
    }
    fun bundleJS(context: Context, isHandleCrash: Boolean = true): String {
      if (isHandleCrash) {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
      }
      val sharedPrefs = SharedPrefs(context)
      val pathBundle = sharedPrefs.getString(PATH)
      val version = sharedPrefs.getString(VERSION)
      val currentVersionName = sharedPrefs.getString(CURRENT_VERSION_CODE)
      val actualVersionCode = context.getVersionCode()

      // 🔍 진단: SharedPreferences 상태 로깅
      Log.i("OtaHotUpdate", "[HOTFIX_DIAGNOSTICS] SharedPrefs state - PATH: $pathBundle, VERSION: $version, STORED_VERSION_CODE: $currentVersionName, ACTUAL_VERSION_CODE: $actualVersionCode")

      if (pathBundle == "" || (currentVersionName != actualVersionCode)) {
        // 🔍 진단: DEFAULT_BUNDLE 반환 사유
        if (pathBundle == "") {
          Log.w("OtaHotUpdate", "[HOTFIX_DIAGNOSTICS] Returning DEFAULT_BUNDLE - Reason: PATH is empty (first install or data cleared)")
        } else {
          Log.w("OtaHotUpdate", "[HOTFIX_DIAGNOSTICS] Returning DEFAULT_BUNDLE - Reason: Version mismatch (stored: $currentVersionName, actual: $actualVersionCode)")
        }

        if (pathBundle != "") {
          Log.i("OtaHotUpdate", "[HOTFIX_DIAGNOSTICS] Clearing stored PATH: $pathBundle")
          sharedPrefs.putString(PATH, "")
        }
        if (version != "") {
          // reset version number because bundle is wrong version, need download from new version
          Log.i("OtaHotUpdate", "[HOTFIX_DIAGNOSTICS] Resetting stored VERSION: $version")
          sharedPrefs.putString(VERSION, "")
        }
        return DEFAULT_BUNDLE
      }

      // 🔍 진단: 파일 존재 여부 검증 (추가)
      val bundleFile = java.io.File(pathBundle!!)
      Log.i("OtaHotUpdate", "[FILE_CHECK] Checking bundle file existence: $pathBundle")
      Log.i("OtaHotUpdate", "[FILE_CHECK] File exists: ${bundleFile.exists()}, Readable: ${bundleFile.canRead()}, Size: ${bundleFile.length()} bytes")

      if (!bundleFile.exists() || !bundleFile.canRead()) {
        // 🔍 진단: 파일 없음 - 대체 경로 탐색
        Log.e("OtaHotUpdate", "[FILE_NOT_FOUND] Bundle file not accessible at: $pathBundle")
        Log.e("OtaHotUpdate", "[FILE_NOT_FOUND] Parent dir: ${bundleFile.parentFile?.absolutePath}")
        Log.e("OtaHotUpdate", "[FILE_NOT_FOUND] Parent exists: ${bundleFile.parentFile?.exists()}")

        // 부모 디렉토리 내용 확인
        val parentDir = bundleFile.parentFile
        if (parentDir?.exists() == true) {
          val filesInParent = parentDir.listFiles()
          Log.e("OtaHotUpdate", "[FILE_NOT_FOUND] Files in parent dir: ${filesInParent?.size ?: 0}")
          filesInParent?.forEach {
            Log.e("OtaHotUpdate", "[FILE_NOT_FOUND] - ${it.name} (isDir: ${it.isDirectory}, size: ${if (it.isFile) it.length() else 0})")
          }
        }

        // files 디렉토리 전체 탐색
        val filesDir = context.filesDir
        Log.e("OtaHotUpdate", "[SEARCHING_BUNDLE] Searching in filesDir: ${filesDir.absolutePath}")
        val allDirs = filesDir.listFiles()?.filter { it.isDirectory }
        allDirs?.forEach { dir ->
          Log.d("OtaHotUpdate", "[SEARCHING_BUNDLE] Checking dir: ${dir.name}")
          val possibleBundle = java.io.File(dir, "index.android.bundle")
          if (possibleBundle.exists()) {
            Log.w("OtaHotUpdate", "[FOUND_ALTERNATIVE] Bundle found at: ${possibleBundle.absolutePath}, Size: ${possibleBundle.length()} bytes")
          }
        }

        // SharedPrefs 초기화
        Log.w("OtaHotUpdate", "[RESET_STATE] Clearing VERSION and PATH due to missing file")
        sharedPrefs.putString(VERSION, "")
        sharedPrefs.putString(PATH, "")
        return DEFAULT_BUNDLE
      }

      // 🔍 진단: 핫픽스 번들 경로 반환
      Log.i("OtaHotUpdate", "[HOTFIX_DIAGNOSTICS] Returning hotfix bundle path: $pathBundle (version: $version)")
      return pathBundle
    }
  }
}
