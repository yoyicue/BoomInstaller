package moe.shizuku.manager.starter

import moe.shizuku.manager.application
import java.io.File

object Starter {

    private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String = "${starterFile.absolutePath} --apk=${application.applicationInfo.sourceDir}"

    val adbCommand = "adb shell $userCommand"

    val internalCommand = userCommand
}
