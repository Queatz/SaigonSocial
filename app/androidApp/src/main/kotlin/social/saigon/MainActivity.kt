package social.saigon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // The API + content base URLs are chosen by the build type (BuildConfig.API_BASE_URL,
            // BuildConfig.CONTENT_BASE_URL): debug -> local dev servers, release -> production.
            // See androidApp/build.gradle.kts.
            App(baseUrl = BuildConfig.API_BASE_URL, contentUrl = BuildConfig.CONTENT_BASE_URL)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
