package id.basokanggito.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.runner.AndroidJUnitRunner
import id.basokanggito.app.data.*
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.view.WindowManager

class NotebookTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        // Enroll a unique fixture through the same bootstrap endpoint before opening MainActivity.
        val isolated = targetContext.applicationContext as IsolatedNotebookApplication
        val proof = "${java.util.UUID.randomUUID()}.${DeviceAccessStore.encode(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })}"
        val bootstrap = testControlClient().post("/test/bootstrap", org.json.JSONObject()).body.getString("token")
        val result = NotebookHttpClient("http://127.0.0.1:3001", proof).post("/v1/access/bootstrap", org.json.JSONObject().put("name", "Isolated UI administrator").put("token", bootstrap))
        check(result.status == 200)
        isolated.access.store.write(org.json.JSONObject().put("credential", proof).put("enrolled", true).put("ready", true).put("role", "admin"))
        isolated.access.store.setPin("135790", "135790"); isolated.access.refresh()
        // Scoped to test windows: never change the user's global screen timeout.
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback { activity, stage ->
            if (stage == Stage.RESUMED) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.window.decorView.keepScreenOn = true
                // Only test-owned windows: no global lock/timeout setting is changed.
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
                }
            }
        }
        // Data-only tests also need an owned window while the physical device is unlocked.
        val keeper = startActivitySync(Intent(targetContext, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync { keeper.setContentView(android.view.View(keeper).apply { keepScreenOn = true }) }
        try { super.onStart() }
        finally { runOnMainSync { keeper.finish() } }
    }
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(loader, IsolatedNotebookApplication::class.java.name, context)
}
class IsolatedNotebookApplication : NotebookApplication() {
    override val backgroundSyncEnabled = false
    private var isolated: NotebookRepository? = null
    override val repository: NotebookRepository get() = isolated ?: newRepository().also { isolated = it }
    private fun newRepository() = NotebookRepository(Room.inMemoryDatabaseBuilder(this, NotebookDatabase::class.java).build())
    fun resetRepository() { isolated?.database?.close(); isolated = newRepository() }
}
