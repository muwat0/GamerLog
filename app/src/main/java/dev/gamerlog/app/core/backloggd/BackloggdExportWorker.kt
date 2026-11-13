package dev.gamerlog.app.core.backloggd

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.gamerlog.app.core.backloggd.security.AndroidBackloggdCredentialStore
import dev.gamerlog.app.core.database.GamerLogDatabase
import kotlinx.coroutines.CancellationException

class BackloggdExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val credentialStore = AndroidBackloggdCredentialStore(applicationContext)
        val engine = BackloggdExportEngine(
            database = GamerLogDatabase.get(applicationContext),
            credentialStore = credentialStore,
            transport = HttpBackloggdTransport(credentialStore)
        )
        return execute(engine)
    }

    internal suspend fun execute(engine: BackloggdExportEngine): Result = try {
        when (engine.run()) {
            BackloggdSyncOutcome.COMPLETE -> Result.success()
            BackloggdSyncOutcome.RETRY -> Result.retry()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: BackloggdException) {
        if (failure.retryable) Result.retry() else Result.failure()
    } catch (_: Exception) {
        Result.retry()
    }
}
