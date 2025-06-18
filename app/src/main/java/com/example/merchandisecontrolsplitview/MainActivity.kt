package com.example.merchandisecontrolsplitview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.merchandisecontrolsplitview.ui.navigation.AppNavGraph
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MerchandiseControlTheme {
                AppNavGraph()
            }
        }
    }
}