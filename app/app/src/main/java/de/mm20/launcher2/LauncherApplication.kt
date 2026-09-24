package de.mm20.launcher2

import android.app.Application
import android.os.Looper
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import de.mm20.launcher2.accounts.accountsModule
import de.mm20.launcher2.applications.applicationsModule
import de.mm20.launcher2.appshortcuts.appShortcutsModule
import de.mm20.launcher2.backup.backupModule
import de.mm20.launcher2.badges.badgesModule
import de.mm20.launcher2.calculator.calculatorModule
import de.mm20.launcher2.calendar.calendarModule
import de.mm20.launcher2.contacts.contactsModule
import de.mm20.launcher2.data.customattrs.customAttrsModule
import de.mm20.launcher2.data.i18nDataModule
import de.mm20.launcher2.searchable.searchableModule
import de.mm20.launcher2.files.filesModule
import de.mm20.launcher2.icons.iconsModule
import de.mm20.launcher2.music.musicModule
import de.mm20.launcher2.search.searchModule
import de.mm20.launcher2.unitconverter.unitConverterModule
import de.mm20.launcher2.websites.websitesModule
import de.mm20.launcher2.widgets.widgetsModule
import de.mm20.launcher2.wikipedia.wikipediaModule
import de.mm20.launcher2.database.databaseModule
import de.mm20.launcher2.debug.initDebugMode
import de.mm20.launcher2.globalactions.globalActionsModule
import de.mm20.launcher2.notifications.notificationsModule
import de.mm20.launcher2.locations.locationsModule
import de.mm20.launcher2.permissions.permissionsModule
import de.mm20.launcher2.data.plugins.dataPluginsModule
import de.mm20.launcher2.devicepose.devicePoseModule
import de.mm20.launcher2.feed.feedModule
import de.mm20.launcher2.plugins.servicesPluginsModule
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.profiles.profilesModule
import de.mm20.launcher2.searchactions.searchActionsModule
import de.mm20.launcher2.services.favorites.favoritesModule
import de.mm20.launcher2.services.tags.servicesTagsModule
import de.mm20.launcher2.services.widgets.widgetsServiceModule
import de.mm20.launcher2.themes.themesModule
import de.mm20.launcher2.weather.weatherModule
import kotlinx.coroutines.*
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import kotlin.coroutines.CoroutineContext

class LauncherApplication : Application(), CoroutineScope, ImageLoaderFactory {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + SupervisorJob()

    override fun onCreate() {
        super.onCreate()

        installRelaunchRaceGuard()

        if (BuildConfig.BUILD_TYPE == "debug") initDebugMode()

        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@LauncherApplication)
            modules(
                listOf(
                    accountsModule,
                    applicationsModule,
                    appShortcutsModule,
                    baseModule,
                    calculatorModule,
                    badgesModule,
                    calendarModule,
                    contactsModule,
                    customAttrsModule,
                    databaseModule,
                    favoritesModule,
                    searchableModule,
                    filesModule,
                    globalActionsModule,
                    iconsModule,
                    musicModule,
                    notificationsModule,
                    permissionsModule,
                    preferencesModule,
                    searchModule,
                    searchActionsModule,
                    themesModule,
                    unitConverterModule,
                    weatherModule,
                    websitesModule,
                    widgetsModule,
                    wikipediaModule,
                    locationsModule,
                    servicesTagsModule,
                    widgetsServiceModule,
                    dataPluginsModule,
                    servicesPluginsModule,
                    backupModule,
                    devicePoseModule,
                    profilesModule,
                    i18nDataModule,
                    feedModule,
                )
            )
        }
    }

    /**
     * As the default Home app, this process must not die from AOSP's long-standing
     * "Can't start activity that is not stopped." race in ActivityThread.handleRelaunchActivityLocally
     * (still open upstream, see MM2-0/Kvaesitso#790). It fires almost exclusively on the main/Home
     * activity because Home is relaunched on every task switch, and some OEM skins (notably Samsung
     * OneUI, see #1464) issue extra overlapping relaunch transactions during their gesture nav
     * animations that race with it. Left unhandled, this crash repeatedly kills the launcher
     * process, which is what causes Android to silently unset it as the default Home app -
     * i.e. exactly the "task switching / returning home becomes unstable" symptom.
     *
     * There is no unstable app state to clean up here: the exception is thrown by the platform
     * before our Activity's relaunch even begins, so nothing of ours has partially run. The only
     * consequence of the crash is that this one relaunch transaction is dropped, which is also
     * the only consequence of catching it here - so we resume the main thread's message loop
     * instead of letting the process die. Anything else is rethrown to the previous handler
     * (crash reporter) unchanged.
     */
    private fun installRelaunchRaceGuard() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (thread !== Looper.getMainLooper().thread || !isRelaunchRace(throwable)) {
                previousHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            var pending = throwable
            while (true) {
                Log.e("MM20", "Ignored relaunch race, see MM2-0/Kvaesitso#790", pending)
                try {
                    Looper.loop()
                    return@setDefaultUncaughtExceptionHandler
                } catch (e: Throwable) {
                    if (!isRelaunchRace(e)) {
                        previousHandler?.uncaughtException(thread, e)
                        return@setDefaultUncaughtExceptionHandler
                    }
                    pending = e
                }
            }
        }
    }

    private fun isRelaunchRace(t: Throwable): Boolean {
        return t is IllegalStateException &&
                t.message == "Can't start activity that is not stopped." &&
                t.stackTrace.any {
                    it.className == "android.app.ActivityThread" &&
                            it.methodName == "handleRelaunchActivityLocally"
                }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(applicationContext)
            .components {
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .crossfade(200)
            .build()
    }
}