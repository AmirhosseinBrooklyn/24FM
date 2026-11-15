package ir.fm24.app

import android.app.Application
import android.os.StrictMode
import ir.fm24.app.network.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the few app-wide collaborators; no DI framework is needed at this size. */
class FmApplication : Application() {

    /** Lives as long as the process; only used for process-wide shared flows. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val connectivity: ConnectivityMonitor by lazy { ConnectivityMonitor(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectNetwork().detectCustomSlowCalls().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build(),
            )
        }
    }
}
