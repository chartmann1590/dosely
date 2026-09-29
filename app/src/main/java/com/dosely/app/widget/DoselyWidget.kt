package com.dosely.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/** The Dosely home-screen widget: next dose, pen stock, latest weight. */
object DoselyWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        provideContent {
            WidgetContent(data)
        }
    }

    @Composable
    private fun WidgetContent(data: WidgetSnapshot) {
        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.background)
                    .padding(14.dp),
            ) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        "DoseLY",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF7BE495)),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text(
                        "GLP-1",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF9FB5AA)),
                            fontSize = 11.sp,
                        ),
                    )
                }
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(
                    "Next injection",
                    style = TextStyle(color = ColorProvider(Color(0xFF9FB5AA)), fontSize = 11.sp),
                )
                Text(
                    data.nextLabel,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFEAF6EE)),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(10.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "Pens",
                            style = TextStyle(color = ColorProvider(Color(0xFF9FB5AA)), fontSize = 10.sp),
                        )
                        Text(
                            data.pens,
                            style = TextStyle(
                                color = ColorProvider(Color(0xFFEAF6EE)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "Weight",
                            style = TextStyle(color = ColorProvider(Color(0xFF9FB5AA)), fontSize = 10.sp),
                        )
                        Text(
                            data.weight,
                            style = TextStyle(
                                color = ColorProvider(Color(0xFFEAF6EE)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}
