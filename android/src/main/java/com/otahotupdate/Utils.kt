package com.otahotupdate

import android.content.Context
import android.icu.text.SimpleDateFormat
import com.rnhotupdate.Common.PREVIOUS_PATH
import com.rnhotupdate.SharedPrefs
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

class Utils internal constructor(private val context: Context) {

  fun deleteDirectory(directory: File): Boolean {
    if (directory.isDirectory) {
      // List all files and directories in the current directory
      val files = directory.listFiles()
      if (files != null) {
        // Recursively delete all files and directories
        for (file in files) {
          if (!deleteDirectory(file)) {
            return false
          }
        }
      }
    }
    // Finally, delete the empty directory or file
    return directory.delete()
  }
  fun deleteOldBundleIfneeded(pathKey: String?): Boolean {
    val pathName = pathKey ?: PREVIOUS_PATH
    val sharedPrefs = SharedPrefs(context)
    val bundlePath = sharedPrefs.getString(pathName)

    if (!bundlePath.isNullOrEmpty()) {
      val bundleFile = File(bundlePath)
      if (bundleFile.exists() && bundleFile.isFile) {
        val outputFolder = bundleFile.parentFile

        // ✅ FIX: 경로 형식 감지 - 타임스탬프 폴더만 전체 삭제, /files 루트는 안전
        if (outputFolder != null &&
            outputFolder.exists() &&
            outputFolder.isDirectory &&
            outputFolder.name.startsWith("output_")) {
          // 새 형식: 타임스탬프 폴더 전체 삭제 (output_YYYYMMDD_HHMMSS)
          android.util.Log.i("Utils", "[DELETE_NEW_FORMAT] Deleting timestamp folder: ${outputFolder.absolutePath}")
          val isDeleted = deleteDirectory(outputFolder)
          sharedPrefs.putString(pathName, "")
          android.util.Log.i("Utils", "[DELETE_NEW_FORMAT] Result: $isDeleted")
          return isDeleted
        } else if (outputFolder != null) {
          // 구 형식 또는 예외 상황: 번들 파일만 삭제 (전체 /files 디렉토리 삭제 방지)
          android.util.Log.i("Utils", "[DELETE_OLD_FORMAT] Deleting bundle file only: ${bundleFile.absolutePath}")
          android.util.Log.i("Utils", "[DELETE_OLD_FORMAT] Parent folder preserved: ${outputFolder.absolutePath}")
          val isDeleted = bundleFile.delete()
          sharedPrefs.putString(pathName, "")
          android.util.Log.i("Utils", "[DELETE_OLD_FORMAT] Result: $isDeleted")
          return isDeleted
        }
      }
    }
    return false
  }

  fun extractZipFile(
    zipFile: File,extension: String
  ): String? {
    return try {
      val outputDir = zipFile.parentFile
      val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
      var topLevelFolder: String? = null
      var bundlePath: String? = null

      // 🔍 진단: 압축 해제 시작
      android.util.Log.i("Utils", "[EXTRACT_START] ZIP: ${zipFile.absolutePath}, Size: ${zipFile.length()} bytes")
      android.util.Log.i("Utils", "[EXTRACT_START] OutputDir: ${outputDir?.absolutePath}")
      android.util.Log.i("Utils", "[EXTRACT_START] Timestamp: $timestamp")

      ZipFile(zipFile).use { zip ->
        val entries = zip.entries().toList()
        // 🔍 진단: ZIP 구조 로깅
        android.util.Log.i("Utils", "[ZIP_STRUCTURE] Total entries: ${entries.size}")
        android.util.Log.i("Utils", "[ZIP_STRUCTURE] First 10 entries: ${entries.take(10).map { it.name }}")

        entries.forEach { entry ->
          zip.getInputStream(entry).use { input ->
            if (topLevelFolder == null) {
              // Get root folder of zip file after unzip
              val parts = entry.name.split("/")
              if (parts.size > 1) {
                topLevelFolder = parts.first()
                // 🔍 진단: 최상위 폴더 감지
                android.util.Log.i("Utils", "[TOP_FOLDER_DETECTED] $topLevelFolder")
              }
            }
            val outputFile = File(outputDir, entry.name)
            if (entry.isDirectory) {
              if (!outputFile.exists()) outputFile.mkdirs()
            } else {
              if (outputFile.parentFile?.exists() != true)  outputFile.parentFile?.mkdirs()
              outputFile.outputStream().use { output ->
                input.copyTo(output)
              }

              // 🔍 진단: 파일 생성 로깅
              android.util.Log.d("Utils", "[FILE_CREATED] ${outputFile.absolutePath}, Size: ${outputFile.length()} bytes")

              if (outputFile.absolutePath.endsWith(extension)) {
                bundlePath = outputFile.absolutePath
                // 🔍 진단: 번들 파일 발견
                android.util.Log.i("Utils", "[BUNDLE_FOUND] Path: $bundlePath, Size: ${outputFile.length()} bytes")
                return@use // Exit early if found
              }
            }
          }
        }
      }

      // 🔍 진단: 리네임 전 상태
      android.util.Log.i("Utils", "[BEFORE_RENAME] TopLevelFolder: $topLevelFolder, BundlePath: $bundlePath")

      // Rename the detected top-level folder
      if (topLevelFolder != null) {
        val extractedFolder = File(outputDir, topLevelFolder)
        val renamedFolder = File(outputDir, "output_$timestamp")

        // 🔍 진단: 리네임 시도 전 상태
        android.util.Log.i("Utils", "[RENAME_ATTEMPT] From: ${extractedFolder.absolutePath}")
        android.util.Log.i("Utils", "[RENAME_ATTEMPT] To: ${renamedFolder.absolutePath}")
        android.util.Log.i("Utils", "[RENAME_STATUS_BEFORE] ExtractedExists: ${extractedFolder.exists()}, ExtractedIsDir: ${extractedFolder.isDirectory}")
        android.util.Log.i("Utils", "[RENAME_STATUS_BEFORE] RenamedExists: ${renamedFolder.exists()}")

        if (extractedFolder.exists()) {
          // 🔍 진단: 폴더 내용 로깅
          val filesInExtracted = extractedFolder.listFiles()
          android.util.Log.i("Utils", "[FOLDER_CONTENTS] Files in extracted folder: ${filesInExtracted?.size ?: 0}")
          filesInExtracted?.take(5)?.forEach {
            android.util.Log.d("Utils", "[FOLDER_CONTENTS] - ${it.name} (${it.length()} bytes)")
          }

          val renameSuccess = extractedFolder.renameTo(renamedFolder)

          // 🔍 진단: 리네임 결과
          android.util.Log.i("Utils", "[RENAME_RESULT] Success: $renameSuccess")
          android.util.Log.i("Utils", "[RENAME_STATUS_AFTER] ExtractedExists: ${extractedFolder.exists()}, RenamedExists: ${renamedFolder.exists()}")

          if (renameSuccess) {
            // ✅ FIX: Move bundle file into timestamp folder
            if (bundlePath != null) {
              val originalBundleFile = File(bundlePath)
              val bundleName = originalBundleFile.name  // "index.android.bundle"

              // Target path: /data/.../files/output_20250115_163531/index.android.bundle
              val targetFile = File(renamedFolder, bundleName)
              val newBundlePath = targetFile.absolutePath

              android.util.Log.i("Utils", "[BUNDLE_MOVE] From: $bundlePath")
              android.util.Log.i("Utils", "[BUNDLE_MOVE] To: $newBundlePath")

              // Move bundle file into timestamp folder
              if (originalBundleFile.exists() && originalBundleFile.isFile) {
                val moveSuccess = originalBundleFile.renameTo(targetFile)

                if (moveSuccess && targetFile.exists() && targetFile.canRead()) {
                  bundlePath = newBundlePath
                  android.util.Log.i("Utils", "[BUNDLE_MOVE] ✅ Bundle moved successfully")
                  android.util.Log.i("Utils", "[BUNDLE_MOVE] Verify - Size: ${targetFile.length()} bytes, Readable: ${targetFile.canRead()}")
                } else {
                  android.util.Log.e("Utils", "[BUNDLE_MOVE] ❌ Move failed, keeping original path")
                  android.util.Log.e("Utils", "[BUNDLE_MOVE] MoveSuccess: $moveSuccess, Exists: ${targetFile.exists()}, Readable: ${targetFile.canRead()}")
                }
              } else {
                android.util.Log.e("Utils", "[BUNDLE_MOVE] ❌ Original bundle file invalid")
                android.util.Log.e("Utils", "[BUNDLE_MOVE] Exists: ${originalBundleFile.exists()}, IsFile: ${originalBundleFile.isFile}")
              }
            }
          } else {
            // 🔍 진단: 리네임 실패 원인 분석
            android.util.Log.e("Utils", "[RENAME_FAILED] Attempting to diagnose failure")
            android.util.Log.e("Utils", "[RENAME_FAILED] ExtractedFolder canRead: ${extractedFolder.canRead()}, canWrite: ${extractedFolder.canWrite()}")
            android.util.Log.e("Utils", "[RENAME_FAILED] Parent dir: ${outputDir?.absolutePath}")
            android.util.Log.e("Utils", "[RENAME_FAILED] Parent canWrite: ${outputDir?.canWrite()}")

            // 부모 디렉토리 내용 확인
            val filesInParent = outputDir?.listFiles()
            android.util.Log.e("Utils", "[RENAME_FAILED] Files in parent dir: ${filesInParent?.size ?: 0}")
            filesInParent?.forEach {
              android.util.Log.e("Utils", "[RENAME_FAILED] - ${it.name} (isDir: ${it.isDirectory})")
            }
          }
        } else {
          android.util.Log.e("Utils", "[RENAME_SKIPPED] Extracted folder does not exist: ${extractedFolder.absolutePath}")
        }
      } else {
        // ✅ FIX: No drawable folder - create timestamp folder directly
        android.util.Log.w("Utils", "[NO_TOP_FOLDER] No top-level folder detected, creating timestamp folder")

        if (bundlePath != null) {
          val timestampFolder = File(outputDir, "output_$timestamp")

          // Create timestamp folder
          if (!timestampFolder.exists()) {
            val mkdirSuccess = timestampFolder.mkdirs()
            android.util.Log.i("Utils", "[CREATE_FOLDER] Timestamp folder created: $mkdirSuccess")
          }

          // Move bundle into timestamp folder
          val originalBundleFile = File(bundlePath)
          val targetFile = File(timestampFolder, originalBundleFile.name)
          val newBundlePath = targetFile.absolutePath

          android.util.Log.i("Utils", "[BUNDLE_MOVE_NO_DRAWABLE] From: $bundlePath")
          android.util.Log.i("Utils", "[BUNDLE_MOVE_NO_DRAWABLE] To: $newBundlePath")

          if (originalBundleFile.exists() && originalBundleFile.isFile) {
            val moveSuccess = originalBundleFile.renameTo(targetFile)

            if (moveSuccess && targetFile.exists() && targetFile.canRead()) {
              bundlePath = newBundlePath
              android.util.Log.i("Utils", "[BUNDLE_MOVE_NO_DRAWABLE] ✅ Bundle moved successfully")
              android.util.Log.i("Utils", "[BUNDLE_MOVE_NO_DRAWABLE] Verify - Size: ${targetFile.length()} bytes")
            } else {
              android.util.Log.e("Utils", "[BUNDLE_MOVE_NO_DRAWABLE] ❌ Move failed, keeping original")
              android.util.Log.e("Utils", "[BUNDLE_MOVE_NO_DRAWABLE] MoveSuccess: $moveSuccess, Exists: ${targetFile.exists()}")
            }
          }
        }
      }

      // 🔍 진단: 최종 검증
      android.util.Log.i("Utils", "[EXTRACT_COMPLETE] Final bundlePath: $bundlePath")
      if (bundlePath != null) {
        val finalFile = File(bundlePath)
        android.util.Log.i("Utils", "[FINAL_VERIFICATION] File exists: ${finalFile.exists()}, Readable: ${finalFile.canRead()}, Size: ${finalFile.length()} bytes")

        if (!finalFile.exists()) {
          android.util.Log.e("Utils", "[FINAL_VERIFICATION_FAILED] Bundle file not found at expected path!")
          android.util.Log.e("Utils", "[FINAL_VERIFICATION_FAILED] Parent dir: ${finalFile.parentFile?.absolutePath}")
          android.util.Log.e("Utils", "[FINAL_VERIFICATION_FAILED] Parent exists: ${finalFile.parentFile?.exists()}")

          // 실제 파일 위치 찾기 시도
          outputDir?.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
              val possibleBundle = File(dir, "index.android.bundle")
              if (possibleBundle.exists()) {
                android.util.Log.w("Utils", "[FOUND_ALTERNATIVE] Bundle found at: ${possibleBundle.absolutePath}")
              }
            }
          }
        }
      } else {
        android.util.Log.e("Utils", "[EXTRACT_COMPLETE] bundlePath is null!")
      }

      bundlePath
    } catch (e: Exception) {
      android.util.Log.e("Utils", "[EXTRACT_ERROR] Exception occurred", e)
      e.printStackTrace()
      null
    }
  }
}
