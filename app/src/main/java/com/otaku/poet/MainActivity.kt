package com.otaku.poet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.otaku.poet.data.PoetViewModel
import com.otaku.poet.theme.ElectronicPoetTheme
import com.otaku.poet.ui.AboutScreen
import com.otaku.poet.ui.ComposeScreen
import com.otaku.poet.ui.PacksScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // API 26-28 写公共 Download/cyberpoet 需要存储权限；API 29+ 用 MediaStore 无需。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
        val appVersion = packageManager
            .getPackageInfo(packageName, 0)
            .versionName ?: "?"

        setContent {
            ElectronicPoetTheme {
                // 绑定到 Activity 的共享 ViewModel，供所有页面共用同一引擎状态。
                val sharedVm: PoetViewModel = viewModel()
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "compose") {
                    composable("compose") {
                        ComposeScreen(
                            onOpenPacks = { navController.navigate("packs") },
                            onOpenAbout = { navController.navigate("about") },
                            vm = sharedVm,
                        )
                    }
                    composable("packs") {
                        PacksScreen(
                            onBack = { navController.popBackStack() },
                            vm = sharedVm,
                        )
                    }
                    composable("about") {
                        AboutScreen(
                            onBack = { navController.popBackStack() },
                            appVersion = appVersion,
                        )
                    }
                }
            }
        }
    }
}
