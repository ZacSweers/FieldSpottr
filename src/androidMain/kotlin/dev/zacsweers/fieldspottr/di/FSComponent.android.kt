package dev.zacsweers.fieldspottr.di

import android.content.Context
import dev.zacsweers.fieldspottr.ContextFSAppDirs
import dev.zacsweers.fieldspottr.FSAppDirs

class AndroidSharedPlatformFSComponent(private val appContext: Context) :
  SharedPlatformFSComponent {
  override fun provideFSAppDirs(): FSAppDirs {
    return ContextFSAppDirs(appContext)
  }
}
