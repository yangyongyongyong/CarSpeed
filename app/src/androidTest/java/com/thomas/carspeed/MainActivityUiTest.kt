package com.thomas.carspeed

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @Test
    fun opacity_seekbar_and_toggle_button_visible_and_interactable() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withId(R.id.sbOpacity)).check(matches(isDisplayed()))
        onView(withId(R.id.btnToggle)).check(matches(isDisplayed()))

        // 滑动透明度条，验证可交互（不依赖权限）
        onView(withId(R.id.sbOpacity)).perform(swipeRight())

        // 点击启动按钮（后续系统权限弹窗由手工或 UIAutomator 覆盖）
        onView(withId(R.id.btnToggle)).perform(click())
    }
}
