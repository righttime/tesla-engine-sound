package com.tesla.enginesound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tesla.enginesound.ui.theme.TeslaDark
import com.tesla.enginesound.ui.theme.TeslaGray
import com.tesla.enginesound.ui.theme.TeslaGrayLight
import com.tesla.enginesound.ui.theme.TeslaWhite

@Composable
fun DataCard(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TeslaGray)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            color = TeslaGrayLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = value,
            color = TeslaWhite,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = unit,
            color = TeslaGrayLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
