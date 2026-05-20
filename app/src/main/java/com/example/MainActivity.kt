package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.data.db.AppDatabase
import com.example.data.repository.PdfRepository
import com.example.ui.screens.FileListScreen
import com.example.ui.screens.PdfReaderScreen
import com.example.ui.screens.PdfSplitterScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PdfViewModel
import com.example.ui.viewmodel.PdfViewModelFactory

sealed class NavigationScreen {
    object Dashboard : NavigationScreen()
    data class Reader(val docId: Int) : NavigationScreen()
    data class Extractor(val docId: Int) : NavigationScreen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Setup Data persistence layer
        val db = AppDatabase.getDatabase(applicationContext)
        val repo = PdfRepository(db.pdfDao())
        
        // 2. Instantiate custom ViewModel
        val factory = PdfViewModelFactory(repo)
        val viewModel = ViewModelProvider(this, factory)[PdfViewModel::class.java]

        setContent {
            MyApplicationTheme {
                var currentScreen by remember { mutableStateOf<NavigationScreen>(NavigationScreen.Dashboard) }

                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        slideInHorizontally { width -> width / 3 } + fadeIn() togetherWith
                        slideOutHorizontally { width -> -width / 3 } + fadeOut()
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "ScreenNavigationTransition"
                ) { screen ->
                    when (screen) {
                        is NavigationScreen.Dashboard -> {
                            FileListScreen(
                                viewModel = viewModel,
                                onViewPdf = { docId ->
                                    currentScreen = NavigationScreen.Reader(docId)
                                },
                                onSplitPdf = { docId ->
                                    currentScreen = NavigationScreen.Extractor(docId)
                                }
                            )
                        }
                        is NavigationScreen.Reader -> {
                            PdfReaderScreen(
                                viewModel = viewModel,
                                docId = screen.docId,
                                onBack = {
                                    currentScreen = NavigationScreen.Dashboard
                                }
                            )
                        }
                        is NavigationScreen.Extractor -> {
                            PdfSplitterScreen(
                                viewModel = viewModel,
                                docId = screen.docId,
                                onBack = {
                                    currentScreen = NavigationScreen.Dashboard
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
