package com.dosely.app.di

import com.dosely.app.ads.AdsManager
import com.dosely.app.ai.CoachEngine
import com.dosely.app.ai.ModelDownloadManager
import com.dosely.app.data.db.DoselyDb
import com.dosely.app.data.feedback.BugReportRepo
import com.dosely.app.data.feedback.FeedbackWorkerApi
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import com.dosely.app.translate.TranslationService
import com.dosely.app.ui.AppViewModel
import com.dosely.app.ui.calendar.CalendarViewModel
import com.dosely.app.ui.coach.CoachViewModel
import com.dosely.app.ui.doses.DosesViewModel
import com.dosely.app.ui.feedback.FeedbackViewModel
import com.dosely.app.ui.home.HomeViewModel
import com.dosely.app.ui.onboarding.OnboardingViewModel
import com.dosely.app.ui.settings.SettingsViewModel
import com.dosely.app.ui.weight.WeightViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { DoselyDb.get(androidContext()) }
    single { SettingsRepository(androidContext()) }
    single { DoselyRepository(get(), get()) }
    single { TranslationService(androidContext()) }
    single { ModelDownloadManager(androidContext()) }
    single { CoachEngine(androidContext()) }
    single { AdsManager.get(androidContext()) }
    single { BugReportRepo(androidContext()) }
    single { FeedbackWorkerApi() }

    viewModelOf(::AppViewModel)
    viewModelOf(::OnboardingViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::DosesViewModel)
    viewModelOf(::WeightViewModel)
    viewModelOf(::CoachViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::CalendarViewModel)
    viewModelOf(::FeedbackViewModel)
}
