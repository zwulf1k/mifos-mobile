/*
 * Copyright 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * See https://github.com/openMF/mobile-mobile/blob/master/LICENSE.md
 */
package cmp.android.app.arci

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cmp.android.app.R

/**
 * IpotekaBank loading mark supplied by the host application.
 *
 * The official OTP Bank roundel turns around its vertical axis. The full official
 * horizontal logo is cropped to its leading square so the source artwork stays intact.
 */
@Composable
internal fun IpotekaArciLoadingIndicator(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "otp-loading-mark")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "otp-loading-mark-rotation",
    )

    Image(
        painter = painterResource(R.drawable.otpbank_logo),
        contentDescription = null,
        modifier = modifier
            .size(128.dp)
            .graphicsLayer {
                rotationY = rotation
                transformOrigin = TransformOrigin.Center
                cameraDistance = 12f * density
            },
        alignment = Alignment.CenterStart,
        contentScale = ContentScale.Crop,
    )
}
