package com.aynthor.taskswap.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TaskResolverTest {

    private val resolver = TaskResolver()

    @Test
    fun parseModernFormat_findsTasksPerDisplay() {
        val dumpsys = """
            Display #0 (activities from top to bottom):
              RootTask #158: type=standard mode=fullscreen
                * Task{2ad2d3b #158 type=standard A=10212:com.example.app1 U=0 visible=true mode=fullscreen translucent=false sz=1}
                  mResumedActivity: ActivityRecord{a926067 u0 com.example.app1/.MainActivity t158}
            Display #1 (activities from top to bottom):
              RootTask #260: type=standard mode=fullscreen
                * Task{abc123 #260 type=standard A=10213:com.example.app2 U=0 visible=true mode=fullscreen translucent=false sz=1}
                  mResumedActivity: ActivityRecord{b926067 u0 com.example.app2/.MainActivity t260}
        """.trimIndent()

        val tasks = resolver.parse(dumpsys)
        assertEquals(2, tasks.size)

        val app1 = tasks.first { it.packageName == "com.example.app1" }
        assertEquals(158, app1.rootTaskId)
        assertEquals(0, app1.displayId)

        val app2 = tasks.first { it.packageName == "com.example.app2" }
        assertEquals(260, app2.rootTaskId)
        assertEquals(1, app2.displayId)
    }

    @Test
    fun parseHistLines_findsSettingsTask() {
        val dumpsys = """
            Display #1 (activities from top to bottom):
              RootTask #77: type=standard mode=fullscreen
                * Task{abc #77 type=standard A=1000:com.android.settings U=0 visible=true mode=fullscreen translucent=false sz=2}
                  Hist #0: ActivityRecord{abc u0 com.android.settings/.SubSettings t77}
                  Hist #1: ActivityRecord{def u0 com.android.settings/.Settings t77}
        """.trimIndent()

        val task = resolver.findForPackage(dumpsys, "com.android.settings", 1)
        assertNotNull(task)
        assertEquals(77, task?.rootTaskId)
        assertEquals(1, task?.displayId)
    }

    @Test
    fun parseForegroundPerDisplay_readsResumedActivity() {
        val dumpsys = """
            Display #0 (activities from top to bottom):
              RootTask #10: type=standard mode=fullscreen
                mResumedActivity: ActivityRecord{aaa u0 com.aynthor.taskswap/.MainActivity t10}
            Display #1 (activities from top to bottom):
              RootTask #20: type=standard mode=fullscreen
                mResumedActivity: ActivityRecord{bbb u0 com.android.settings/.Settings t20}
        """.trimIndent()

        val foreground = resolver.parseForegroundPerDisplay(dumpsys, emptySet())
        assertEquals("com.aynthor.taskswap", foreground[0])
        assertEquals("com.android.settings", foreground[1])
    }

    @Test
    fun resolveDisplayApps_filtersPackagesWithoutTasks() {
        val dumpsys = """
            Display #0 (activities from top to bottom):
              RootTask #10: type=standard mode=fullscreen
                mResumedActivity: ActivityRecord{aaa u0 com.foo/.MainActivity t10}
            Display #1 (activities from top to bottom):
              RootTask #20: type=standard mode=fullscreen
        """.trimIndent()

        val apps = resolver.resolveDisplayApps(dumpsys, emptySet())
        assertEquals(1, apps.size)
        assertEquals("com.foo", apps[0])
    }

    @Test
    fun resolveDisplayApps_fillsFromParsedTasks() {
        val dumpsys = """
            Display #0 (activities from top to bottom):
              RootTask #10: type=standard mode=fullscreen
                * Task{aaa #10 type=standard A=100:com.foo U=0 visible=true mode=fullscreen translucent=false sz=1}
            Display #1 (activities from top to bottom):
              RootTask #20: type=standard mode=fullscreen
                * Task{bbb #20 type=standard A=100:com.bar U=0 visible=true mode=fullscreen translucent=false sz=1}
        """.trimIndent()

        val apps = resolver.resolveDisplayApps(dumpsys, emptySet())
        assertEquals(2, apps.size)
        assertEquals("com.foo", apps[0])
        assertEquals("com.bar", apps[1])
    }

    @Test
    fun parseStackList_findsAppsPerDisplay() {
        val stackList = """
            RootTask id=10 type=standard mode=fullscreen
              Accounts for 1 display: Display #0
              * Task{aaa #10 type=standard A=100:com.foo U=0 visible=true mode=fullscreen translucent=false sz=1}
            RootTask id=20 type=standard mode=fullscreen
              Accounts for 1 display: Display #1
              * Task{bbb #20 type=standard A=100:com.bar U=0 visible=true mode=fullscreen translucent=false sz=1}
        """.trimIndent()

        val apps = resolver.parseStackList(stackList, emptySet())
        assertEquals("com.foo", apps[0])
        assertEquals("com.bar", apps[1])
    }
}
