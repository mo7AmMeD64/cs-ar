package com.viola

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VioLaPlugin : Plugin() {

    companion object {
        const val TELEGRAM = "https://t.me/fullappk"
        const val DONATE = "https://creators.sa/x3onq"

        fun dialog(ctx: Context) {
            try {
                AlertDialog.Builder(ctx)
                    .setTitle("Vio-La 💜")
                    .setMessage(
                        "انضم لقناة التليجرام لكل جديد و الدعم:\n" +
                            "t.me/fullappk\n\n" +
                            "لدعم استمرار الإضافة و التحديثات تبرع لنا عبر:\n" +
                            "creators.sa/x3onq"
                    )
                    .setPositiveButton("قناة التليجرام") { _, _ -> open(ctx, TELEGRAM) }
                    .setNeutralButton("تبرع لنا 💚") { _, _ -> open(ctx, DONATE) }
                    .setNegativeButton("إغلاق", null)
                    .show()
            } catch (_: Throwable) {
            }
        }

        private fun open(ctx: Context, url: String) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Throwable) {
            }
        }
    }

    override fun load() {
        registerMainAPI(VioLaProvider())
        // the Telegram/Donate dialog opens when the user taps the extension in Settings
        openSettings = { ctx -> dialog(ctx) }
    }
}
