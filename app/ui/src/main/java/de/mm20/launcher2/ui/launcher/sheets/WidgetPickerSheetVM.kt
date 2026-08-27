package de.mm20.launcher2.ui.launcher.sheets

import de.mm20.launcher2.services.widgets.WidgetsService
import android.appwidget.AppWidgetProviderInfo
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.mm20.launcher2.search.ResultScore
import de.mm20.launcher2.search.StringNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.text.Collator

class WidgetPickerSheetVM(
    private val widgetsService: WidgetsService,
    private val packageManager: PackageManager,
    private val stringNormalizer: StringNormalizer,
) : ViewModel() {

    val searchQuery = MutableStateFlow("")

    private val enabledWidgets = widgetsService.getWidgets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(100), emptyList())

    private val allBuiltInWidgets =
        widgetsService.getAvailableBuiltInWidgets()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    private val normalizedBuiltInWidgets = allBuiltInWidgets
        .map { widgets ->
            withContext(Dispatchers.Default) {
                widgets.map {
                    it to (stringNormalizer.normalizeVariants(it.label) + it.type)
                }
            }
        }

    val builtInWidgets = normalizedBuiltInWidgets
        .combine(searchQuery) { widgets, query ->
            if (query.isBlank()) return@combine widgets.map { it.first }
            withContext(Dispatchers.Default) {
                val normalizedQuery = stringNormalizer.normalizeVariants(query)
                widgets.mapNotNull { (widget, labels) ->
                    widget.takeIf {
                        ResultScore.from(
                            queries = normalizedQuery,
                            primaryFields = labels,
                        ).score >= 0.8f
                    }
                }
            }
        }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    /**
     * A widget provider together with everything that is needed to search and group it. Loading a
     * label goes through the package manager, so it is done once per provider instead of once per
     * keystroke.
     */
    private class NormalizedAppWidget(
        val provider: AppWidgetProviderInfo,
        val label: String,
        /**
         * Label of the app the widget belongs to, or null if the app could not be resolved.
         */
        val appName: String?,
        val normalizedLabels: List<String>,
        val normalizedAppLabels: List<String>?,
    )

    private val allAppWidgets = flow {
        val widgets = widgetsService.getAppWidgetProviders()
        emit(widgets)
    }
        .map { widgets ->
            withContext(Dispatchers.IO) {
                widgets.map { provider ->
                    val label = provider.loadLabel(packageManager)
                    val appName = try {
                        packageManager.getApplicationInfo(provider.provider.packageName, 0)
                            .loadLabel(packageManager).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                    NormalizedAppWidget(
                        provider = provider,
                        label = label,
                        appName = appName,
                        normalizedLabels = stringNormalizer.normalizeVariants(label),
                        normalizedAppLabels = appName?.let { stringNormalizer.normalizeVariants(it) },
                    )
                }
            }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    private val filteredAppWidgets = allAppWidgets
        .combine(searchQuery) { widgets, query ->
            if (query.isBlank()) return@combine widgets
            withContext(Dispatchers.Default) {
                val normalizedQuery = stringNormalizer.normalizeVariants(query)
                widgets.filter {
                    if (it.normalizedLabels.any { label ->
                            normalizedQuery.any { q -> label.contains(q) }
                        }) {
                        return@filter true
                    }
                    val normalizedAppLabels = it.normalizedAppLabels ?: return@filter false

                    ResultScore.from(
                        queries = normalizedQuery,
                        primaryFields = it.normalizedLabels + normalizedAppLabels,
                    ).score >= 0.8f
                }
            }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    val expandAllGroups = filteredAppWidgets.map {
        it.size < 10
    }

    val appWidgetGroups = filteredAppWidgets.map { widgets ->
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        withContext(Dispatchers.Default) {
            widgets
                .sortedWith { el1, el2 ->
                    collator.compare(el1.label, el2.label)
                }
                .groupBy {
                    it.provider.provider.packageName
                }
                .map {
                    val pkg = it.key
                    val appName = it.value.firstNotNullOfOrNull { widget -> widget.appName }
                        ?: return@map AppWidgetGroup("", pkg, emptyList())
                    AppWidgetGroup(appName, pkg, it.value.map { widget -> widget.provider })
                }
                .sortedWith { el1, el2 ->
                    collator.compare(el1.appName, el2.appName)
                }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    val expandedGroup = mutableStateOf<String?>(null)

    fun toggleGroup(group: String) {
        expandedGroup.value = if (expandedGroup.value == group) null else group
    }

    fun search(query: String) {
        searchQuery.value = query
    }

    companion object : KoinComponent {
        val Factory = viewModelFactory {
            initializer {
                WidgetPickerSheetVM(get(), get(), get())
            }
        }
    }

}

data class AppWidgetGroup(
    val appName: String,
    val packageName: String,
    val widgets: List<AppWidgetProviderInfo>
)

data class BuiltInWidgetInfo(
    val type: String,
    @StringRes val label: Int,
    val icon: ImageVector
)