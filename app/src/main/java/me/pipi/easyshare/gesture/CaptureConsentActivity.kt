package me.pipi.easyshare.gesture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import me.pipi.easyshare.ui.theme.EasyShareTheme

/**
 * Transparent one-shot page that obtains the mandatory MediaProjection
 * consent for the screenshot fallback (QQ/WeChat without Shizuku). The
 * result is handed back through [CaptureSessionContext] and the page
 * finishes immediately, so the photo page stays in front.
 */
class CaptureConsentActivity : ComponentActivity() {
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureSessionContext.completeProjectionConsent(result.resultCode to data)
        } else {
            CaptureSessionContext.completeProjectionConsent(null)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EasyShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) { }
            }
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val intent = manager?.createScreenCaptureIntent()
        if (intent == null) {
            CaptureSessionContext.completeProjectionConsent(null)
            finish()
            return
        }
        consentLauncher.launch(intent)
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, CaptureConsentActivity::class.java)
    }
}
