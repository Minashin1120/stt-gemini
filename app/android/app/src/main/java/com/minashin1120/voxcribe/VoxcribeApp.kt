package com.minashin1120.voxcribe

import android.app.Application
import com.minashin1120.voxcribe.ai.AiRunner
import com.minashin1120.voxcribe.data.AudioStore
import com.minashin1120.voxcribe.data.Db
import com.minashin1120.voxcribe.data.Prefs
import com.minashin1120.voxcribe.data.SecretStore
import com.minashin1120.voxcribe.task.RetentionCleaner
import com.minashin1120.voxcribe.ui.common.Toaster
import com.minashin1120.voxcribe.ui.workspace.WorkspaceController
import com.minashin1120.voxcribe.update.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VoxcribeApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var db: Db
    lateinit var prefs: Prefs
    lateinit var secrets: SecretStore
    lateinit var audio: AudioStore
    lateinit var runner: AiRunner
    lateinit var toaster: Toaster
    lateinit var workspace: WorkspaceController
    lateinit var updates: UpdateController

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Db(this)
        prefs = Prefs(this)
        secrets = SecretStore(this)
        audio = AudioStore(this)
        runner = AiRunner(db, prefs, secrets, audio)
        toaster = Toaster()
        workspace = WorkspaceController(this)
        updates = UpdateController(this)
        RetentionCleaner.start(this)
    }

    companion object {
        lateinit var instance: VoxcribeApp
            private set
    }
}
