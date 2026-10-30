package dev.kunnect.fixture
import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.content.Intent
class FixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 80, 48, 48) }
        layout.addView(TextView(this).apply { text = "Teather test messages"; textSize = 26f })
        listOf("Post message" to false, "Remove message" to true).forEach { (label, cancel) -> layout.addView(Button(this).apply { text = label; setOnClickListener { sendBroadcast(Intent(this@FixtureActivity, FixtureReceiver::class.java).putExtra("cancel", cancel)) } }) }
        setContentView(layout)
    }
}
