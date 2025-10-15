package com.otahotupdate

import android.content.Context
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.jakewharton.processphoenix.ProcessPhoenix
import com.otahotupdate.OtaHotUpdate.Companion.getVersionCode
import com.rnhotupdate.Common.CURRENT_VERSION_CODE
import com.rnhotupdate.Common.PATH
import com.rnhotupdate.Common.PREVIOUS_PATH
import com.rnhotupdate.Common.VERSION
import com.rnhotupdate.Common.PREVIOUS_VERSION
import com.rnhotupdate.Common.METADATA
import com.rnhotupdate.SharedPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OtaHotUpdateModule internal constructor(context: ReactApplicationContext) :
  OtaHotUpdateSpec(context) {
  private val utils: Utils = Utils(context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun getName(): String {
    return NAME
  }

  override fun invalidate() {
    super.invalidate()
    scope.cancel()
  }

  private fun processBundleFile(path: String?, extension: String?): Boolean {
    // 🔍 진단: processBundleFile 시작
    android.util.Log.i("OtaHotUpdateModule", "[PROCESS_START] Path: $path, Extension: $extension")

    if (path != null) {
      val file = File(path)
      android.util.Log.i("OtaHotUpdateModule", "[PROCESS_CHECK] File exists: ${file.exists()}, Is file: ${file.isFile}, Size: ${file.length()}")

      if (file.exists() && file.isFile) {
        // ✅ FIX: Delete old bundle BEFORE creating new one
        val sharedPrefs = SharedPrefs(reactApplicationContext)
        val oldPath = sharedPrefs.getString(PATH)

        android.util.Log.i("OtaHotUpdateModule", "[DELETE_OLD_BEFORE] Current PATH: $oldPath")

        if (!oldPath.isNullOrEmpty()) {
          // Save to PREVIOUS_PATH before deletion
          sharedPrefs.putString(PREVIOUS_PATH, oldPath)
          android.util.Log.i("OtaHotUpdateModule", "[DELETE_OLD_BEFORE] Saved to PREVIOUS_PATH")

          // Delete old bundle safely
          val deleteSuccess = utils.deleteOldBundleIfneeded(PREVIOUS_PATH)
          android.util.Log.i("OtaHotUpdateModule", "[DELETE_OLD_BEFORE] Deletion result: $deleteSuccess")
        } else {
          android.util.Log.i("OtaHotUpdateModule", "[DELETE_OLD_BEFORE] No previous bundle to delete")
        }

        // 🔍 진단: 압축 해제 호출
        android.util.Log.i("OtaHotUpdateModule", "[CALLING_EXTRACT] Calling extractZipFile...")
        val fileUnzip = utils.extractZipFile(file, extension ?: ".bundle")
        android.util.Log.i("OtaHotUpdateModule", "[EXTRACT_RETURNED] Result: $fileUnzip")

        if (fileUnzip != null) {
          // 🔍 진단: 압축 해제된 파일 검증
          val unzippedFile = File(fileUnzip)
          android.util.Log.i("OtaHotUpdateModule", "[UNZIPPED_VERIFICATION] File exists: ${unzippedFile.exists()}, Readable: ${unzippedFile.canRead()}, Size: ${unzippedFile.length()}")

          if (!unzippedFile.exists() || !unzippedFile.canRead()) {
            file.delete()
            val error = "Extracted file not accessible: $fileUnzip (exists: ${unzippedFile.exists()}, readable: ${unzippedFile.canRead()})"
            android.util.Log.e("OtaHotUpdateModule", "[VERIFICATION_FAILED] $error")
            throw Exception(error)
          }

          // 🔍 진단: ZIP 파일 삭제
          android.util.Log.i("OtaHotUpdateModule", "[DELETE_ZIP] Deleting original zip file: ${file.absolutePath}")
          file.delete()

          // 🔍 진단: 새 PATH 설정
          android.util.Log.i("OtaHotUpdateModule", "[SET_PATH] Setting new PATH: $fileUnzip")
          sharedPrefs.putString(PATH, fileUnzip)

          // 🔍 진단: VERSION_CODE 설정
          val versionCode = reactApplicationContext.getVersionCode()
          android.util.Log.i("OtaHotUpdateModule", "[SET_VERSION_CODE] Setting CURRENT_VERSION_CODE: $versionCode")
          sharedPrefs.putString(CURRENT_VERSION_CODE, versionCode)

          // 🔍 진단: SharedPrefs 저장 후 검증
          val verifyPath = sharedPrefs.getString(PATH)
          val verifyVersionCode = sharedPrefs.getString(CURRENT_VERSION_CODE)
          android.util.Log.i("OtaHotUpdateModule", "[VERIFY_SHAREDPREFS] PATH: $verifyPath, VERSION_CODE: $verifyVersionCode")

          if (verifyPath != fileUnzip) {
            val error = "PATH verification failed! Expected: $fileUnzip, Got: $verifyPath"
            android.util.Log.e("OtaHotUpdateModule", "[VERIFICATION_FAILED] $error")
            throw Exception(error)
          }

          // 🔍 진단: 최종 파일 존재 확인
          val finalCheck = File(verifyPath!!)
          if (!finalCheck.exists() || !finalCheck.canRead()) {
            val error = "Final verification failed! File not accessible at: $verifyPath"
            android.util.Log.e("OtaHotUpdateModule", "[FINAL_CHECK_FAILED] $error")
            throw Exception(error)
          }

          android.util.Log.i("OtaHotUpdateModule", "[PROCESS_SUCCESS] All checks passed!")
          return true
        } else {
          file.delete()
          val error = "File unzip failed or path is invalid: $file"
          android.util.Log.e("OtaHotUpdateModule", "[PROCESS_FAILED] $error")
          throw Exception(error)
        }
      } else {
        val error = "File not exist: $file (exists: ${file.exists()}, isFile: ${file.isFile})"
        android.util.Log.e("OtaHotUpdateModule", "[PROCESS_FAILED] $error")
        throw Exception(error)
      }
    } else {
      val error = "Invalid path: $path"
      android.util.Log.e("OtaHotUpdateModule", "[PROCESS_FAILED] $error")
      throw Exception(error)
    }
  }
  @ReactMethod
  override fun setupBundlePath(path: String?, extension: String?, promise: Promise) {
    scope.launch {
      try {
        val result = processBundleFile(path, extension)
        withContext(Dispatchers.Main) {
          promise.resolve(result)
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          promise.reject("SET_ERROR", e)
        }
      }
    }
  }

  @ReactMethod
  override fun deleteBundle(i: Double, promise: Promise) {
    val isDeleted = utils.deleteOldBundleIfneeded(PATH)
    val isDeletedOldPath = utils.deleteOldBundleIfneeded(PREVIOUS_PATH)
    val sharedPrefs = SharedPrefs(reactApplicationContext)
    sharedPrefs.putString(VERSION, "0")
    promise.resolve(isDeleted && isDeletedOldPath)
  }

  @ReactMethod
  override fun restart() {
    val context: Context? = currentActivity
    ProcessPhoenix.triggerRebirth(context)
  }

  @ReactMethod
  override fun getCurrentVersion(a: Double, promise: Promise) {
    val sharedPrefs = SharedPrefs(reactApplicationContext)
    val version = sharedPrefs.getString(VERSION)
    if (version != "") {
      promise.resolve(version)
    } else {
      promise.resolve("0")
    }
  }

  @ReactMethod
  override fun setCurrentVersion(version: String?, promise: Promise) {
    val sharedPrefs = SharedPrefs(reactApplicationContext)

    val currentVersion = sharedPrefs.getString(VERSION)
    if (currentVersion != "" && currentVersion != version) {
      sharedPrefs.putString(PREVIOUS_VERSION, currentVersion)
    }

    sharedPrefs.putString(VERSION, version)
    promise.resolve(true)
  }

  @ReactMethod
  override fun getUpdateMetadata(a: Double, promise: Promise) {
    val sharedPrefs = SharedPrefs(reactApplicationContext)
    val metadata = sharedPrefs.getString(METADATA)
    if (metadata != "") {
      promise.resolve(metadata);
    } else {
      promise.resolve(null);
    }
  }

  @ReactMethod
  override fun setUpdateMetadata(metadata: String?, promise: Promise) {
    val sharedPrefs = SharedPrefs(reactApplicationContext)
    sharedPrefs.putString(METADATA, metadata)
    promise.resolve(true)
  }


  @ReactMethod
  override fun setExactBundlePath(path: String?, promise: Promise) {
    val file = File(path)
    if (file.exists() && file.isFile) {
      val sharedPrefs = SharedPrefs(reactApplicationContext)
      sharedPrefs.putString(PATH, path)
      sharedPrefs.putString(
        CURRENT_VERSION_CODE,
        reactApplicationContext.getVersionCode()
      )
      promise.resolve(true)
    } else {
      promise.resolve(false)
    }
  }

  @ReactMethod
  override fun rollbackToPreviousBundle(a: Double, promise: Promise) {
    val sharedPrefs = SharedPrefs(reactApplicationContext)
    val oldPath = sharedPrefs.getString(PREVIOUS_PATH)
    val previousVersion = sharedPrefs.getString(PREVIOUS_VERSION)

    if (oldPath != "") {
      val isDeleted = utils.deleteOldBundleIfneeded(PATH)
      if (isDeleted) {
        sharedPrefs.putString(PATH, oldPath)
        sharedPrefs.putString(PREVIOUS_PATH, "")

        if (previousVersion != "") {
          sharedPrefs.putString(VERSION, previousVersion)
          sharedPrefs.putString(PREVIOUS_VERSION, "")
        } else {
          sharedPrefs.putString(VERSION, "")
        }

        promise.resolve(true)
      } else {
        promise.resolve(false)
      }
    } else {
      promise.resolve(false)
    }
  }
  companion object {
    const val NAME = "OtaHotUpdate"
  }
}
