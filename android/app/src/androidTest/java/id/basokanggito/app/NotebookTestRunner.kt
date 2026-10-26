package id.basokanggito.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.runner.AndroidJUnitRunner
import id.basokanggito.app.data.*
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.view.WindowManager

class NotebookTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        // Scoped to test windows: never change the user's global screen timeout.
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback { activity, stage ->
            if (stage == Stage.RESUMED) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        super.onStart()
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
