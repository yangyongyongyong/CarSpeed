package com.thomas.carspeed

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionUiAutomatorTest {

    @Test
    fun auto_accept_common_permission_dialogs() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // 尝试多轮点击“允许”类按钮，覆盖不同 ROM 文案
        repeat(6) {
            clickIfExists(device, "允许")
            clickIfExists(device, "仅在使用该应用时允许")
            clickIfExists(device, "始终允许")
            clickIfExists(device, "Allow")
            clickIfExists(device, "Allow only while using the app")
            clickIfExists(device, "While using the app")
            clickIfExists(device, "始终")
            clickIfExists(device, "确定")
            device.waitForIdle()
        }

        // Android 11+ 有时会出现系统权限页顶部按钮
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            clickIfExists(device, "使用时允许")
            clickIfExists(device, "仅使用期间允许")
        }
    }

    private fun clickIfExists(device: UiDevice, text: String) {
        val obj = device.wait(Until.findObject(By.textContains(text)), 600)
        obj?.click()
    }
}
