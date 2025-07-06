package org.multipaz.identityreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import org.multipaz.context.initializeApplication

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)

        // Because our launchMode is `singleTop` a new MainActivity is launched when being
        // intented from e.g. the Camera app for a mdoc:// URL
        //
        val invokedForMdocUrl = if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString!!
        } else {
            null
        }
        println("XXXY onCreate with $invokedForMdocUrl")
        val app = App(urlLaunchData = invokedForMdocUrl?.let { UrlLaunchData(
            url = invokedForMdocUrl,
            finish = { finish() }
        )})
        setContent {
            app.Content()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        println("XXXY onDestroy")
    }

    override fun onNewIntent(intent: Intent) {
        println("XXXY onNewIntent")
        super.onNewIntent(intent)
    }
}
