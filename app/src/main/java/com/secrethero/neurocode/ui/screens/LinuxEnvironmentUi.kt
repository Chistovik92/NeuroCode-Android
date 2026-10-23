package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.secrethero.neurocode.R
import com.secrethero.neurocode.terminal.ProotFailure
import com.secrethero.neurocode.terminal.ProotProgress
import com.secrethero.neurocode.terminal.ProotStage
import com.secrethero.neurocode.terminal.ProotState

private const val BYTES_IN_MB = 1024L * 1024

private fun stageLabel(stage: ProotStage): Int = when (stage) {
    ProotStage.PRECHECK -> R.string.linux_stage_precheck
    ProotStage.PROOT -> R.string.linux_stage_proot
    ProotStage.ROOTFS -> R.string.linux_stage_rootfs
    ProotStage.EXTRACT -> R.string.linux_stage_extract
    ProotStage.VERIFY -> R.string.linux_stage_verify
}

/** Причина сбоя установки Linux-окружения или прогресс загрузки; для остальных состояний ничего не рисует. */
@Composable
internal fun LinuxEnvironmentDetails(
    state: ProotState,
    failure: ProotFailure?,
    progress: ProotProgress?,
) {
    if (state == ProotState.UNAVAILABLE && failure != null) {
        Text(
            text = stringResource(R.string.linux_failure, stringResource(stageLabel(failure.stage)), failure.detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state == ProotState.PREPARING && progress != null) {
        Text(
            text = stringResource(
                R.string.linux_progress,
                stringResource(stageLabel(progress.stage)),
                progress.downloadedBytes / BYTES_IN_MB,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
