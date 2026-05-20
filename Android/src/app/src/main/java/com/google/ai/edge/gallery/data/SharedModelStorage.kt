/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.data

import android.os.Environment
import java.io.File

/**
 * Shared on-device model storage used by both Gallery builds.
 *
 * The path lives outside either app's private sandbox so the dev build and the Play Store build can
 * point at the same files.
 */
object SharedModelStorage {
  private const val SHARED_MODEL_ROOT_PATH =
    "/storage/emulated/0/Android/media/GoogleAIEdgeGallery/models"

  fun sharedRootDir(): File = File(SHARED_MODEL_ROOT_PATH)

  fun isSharedModelPath(path: String): Boolean {
    val normalizedPath = path.trimEnd(File.separatorChar)
    val rootPath = sharedRootDir().absolutePath.trimEnd(File.separatorChar)
    return normalizedPath == rootPath || normalizedPath.startsWith("$rootPath${File.separator}")
  }

  fun hasSharedStorageAccess(): Boolean = Environment.isExternalStorageManager()

  fun ensureWorldReadable(file: File) {
    if (!file.exists()) {
      return
    }
    file.setReadable(true, false)
    if (file.isDirectory) {
      file.setExecutable(true, false)
      file.listFiles()?.forEach { child -> ensureWorldReadable(child) }
    }
  }

  fun modelRootDir(modelDir: String): File = File(sharedRootDir(), modelDir)

  fun modelDir(model: Model): File = File(sharedRootDir(), model.normalizedName)

  fun modelVersionDir(model: Model): File = File(modelDir(model), model.version)

  fun modelFile(model: Model, fileName: String = model.downloadFileName): File =
    File(modelVersionDir(model), fileName)

  fun versionDir(model: Model, version: String): File = File(modelDir(model), version)

  fun versionDir(modelDir: String, version: String): File = File(modelRootDir(modelDir), version)

  fun versionFile(model: Model, version: String, fileName: String): File =
    File(versionDir(model, version), fileName)

  fun versionFile(modelDir: String, version: String, fileName: String): File =
    File(versionDir(modelDir, version), fileName)

  fun legacyAppRootDir(contextPackageRoot: File?): File? = contextPackageRoot
}
