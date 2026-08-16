package io.omnishield

import android.app.Application
import android.util.Log
import io.omnishield.data.FilterRefreshWorker

class OmniShieldApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Guarded deliberately. `WorkManager.getInstance` throws if its startup initializer
        // has not run — which happens under test, and can happen on a device if the
        // initializer is disabled or racing app startup. Letting that propagate would crash
        // the whole app on launch for the sake of a background list refresh, which is about
        // the worst possible trade. Scheduling is idempotent (KEEP), so a missed attempt is
        // picked up on the next launch.
        runCatching { FilterRefreshWorker.schedule(this) }
            .onFailure { Log.w(TAG, "could not schedule filter refresh: ${it.message}") }
    }

    private companion object {
        const val TAG = "OmniShieldApp"
    }
}
