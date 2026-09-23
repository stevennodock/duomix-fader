// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.dirtwing.duomixfader.BuildConfig
import com.dirtwing.duomixfader.R
import com.dirtwing.duomixfader.ShellCapabilities

private const val PREHN_VIDEO = "https://youtu.be/Vq2xt2D3e3E"
private const val PREHN_YOUTUBE = "https://www.youtube.com/@NewJazz"
private const val PREHN_PATREON = "https://www.patreon.com/newjazz"
private const val PREHN_SITE = "https://www.newjazz.dk"
private const val AUTHOR_EMAIL = "stb@outlook.fr"
/** Page de soutien de l'auteur (Ko-fi) ; laisser vide pour masquer le lien. */
private const val AUTHOR_COFFEE = ""

/**
 * Écran « À propos », sur le modèle de celui de Markor (Gregor Santner) : les informations de
 * build copiables en un geste pour un rapport de bogue, puis ce qu'un utilisateur est en droit
 * de savoir avant de confier des privilèges à l'app — pourquoi Shizuku, ce que fait exactement le
 * service privilégié et avec quels droits, ce qui change avant Android 13 —, le lien vers les
 * sources, les contributeurs, et enfin les licences (NOTICE et LICENSE embarqués tels quels).
 *
 * [capabilities] : ce que le shell peut faire sur CET appareil (voir ShellCapabilities), pour
 * que le rapport de bogue le dise.
 */
@Composable
fun AboutScreen(capabilities: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenses = remember {
        listOf("NOTICE", "LICENSE").map { name ->
            runCatching {
                context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }.getOrDefault(context.getString(R.string.licenses_missing, name))
        }
    }
    val build = buildInformation(context, capabilities)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        }

        // --- Informations de build, copiables ---
        Section(stringResource(R.string.about_build_title)) {
            Text(build, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("DuoMix Fader build", build))
                Toast.makeText(context, R.string.about_build_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.about_build_copy)) }
        }

        // --- Pourquoi Shizuku, et avec quels droits ---
        Section(stringResource(R.string.about_why_title)) {
            Text(stringResource(R.string.about_why_text), style = MaterialTheme.typography.bodyMedium)
        }
        Section(stringResource(R.string.about_methods_title)) {
            Text(stringResource(R.string.about_methods_text), style = MaterialTheme.typography.bodyMedium)
        }
        Section(stringResource(R.string.about_compat_title)) {
            Text(stringResource(R.string.about_compat_text), style = MaterialTheme.typography.bodyMedium)
        }

        // --- Sources ---
        Section(stringResource(R.string.about_source_title)) {
            Text(stringResource(R.string.about_source_text), style = MaterialTheme.typography.bodyMedium)
            Link(BuildConfig.SOURCE_URL, BuildConfig.SOURCE_URL)
        }

        // --- Contributeurs : chacun avec sa part et sa valeur ajoutée ---
        Section(stringResource(R.string.about_contributors_title)) {
            Contributor("Oliver Prehn (NewJazz)", stringResource(R.string.about_contrib_prehn_role), stringResource(R.string.about_contrib_prehn_text)) {
                Link(stringResource(R.string.about_contrib_prehn_youtube), PREHN_YOUTUBE)
                Link(stringResource(R.string.harmony_credits_video), PREHN_VIDEO)
                Link(stringResource(R.string.harmony_credits_patreon), PREHN_PATREON)
                Link("newjazz.dk", PREHN_SITE)
            }
            Contributor("Steve Nodock", stringResource(R.string.about_contrib_steve_role), stringResource(R.string.about_contrib_steve_text)) {
                Link(AUTHOR_EMAIL, "mailto:$AUTHOR_EMAIL")
                if (AUTHOR_COFFEE.isNotEmpty()) Link(stringResource(R.string.about_contrib_coffee), AUTHOR_COFFEE)
            }
            Contributor("Claude (Anthropic)", stringResource(R.string.about_contrib_claude_role), stringResource(R.string.about_contrib_claude_text)) {}
        }

        // --- Licences : NOTICE puis LICENSE, tels qu'embarqués ---
        Section(stringResource(R.string.about_licenses_title)) {
            for (text in licenses) {
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Ce que l'on colle dans un rapport de bogue : l'APK, ses sources, l'appareil, et ce que le shell y peut. */
private fun buildInformation(context: Context, capabilities: Int): String {
    fun right(bit: Int, label: Int) = "${if (capabilities and bit != 0) "+" else "-"} ${context.getString(label)}"
    return listOf(
        "${context.getString(R.string.about_build_package)}: ${BuildConfig.APPLICATION_ID}",
        "${context.getString(R.string.about_build_version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}",
        "${context.getString(R.string.about_build_date)}: ${BuildConfig.BUILD_DATE}",
        "${context.getString(R.string.about_build_commit)}: ${BuildConfig.GIT_HASH}",
        "${context.getString(R.string.about_build_android)}: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "${context.getString(R.string.about_build_device)}: ${Build.MANUFACTURER} ${Build.MODEL}",
        context.getString(R.string.about_build_rights),
        right(ShellCapabilities.FOCUS, R.string.about_rights_focus),
        right(ShellCapabilities.PLAYERS, R.string.about_rights_players),
        right(ShellCapabilities.CAPTURE, R.string.about_rights_capture),
    ).joinToString("\n")
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Contributor(name: String, role: String, text: String, links: @Composable () -> Unit) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(role, style = MaterialTheme.typography.labelMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        links()
    }
}

@Composable
private fun Link(label: String, url: String) {
    val context = LocalContext.current
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            .padding(vertical = 4.dp),
    )
}
