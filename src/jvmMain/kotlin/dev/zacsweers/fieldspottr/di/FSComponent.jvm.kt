package dev.zacsweers.fieldspottr.di

import dev.zacsweers.fieldspottr.DesktopFSAppDirs
import dev.zacsweers.fieldspottr.FSAppDirs

class JvmSharedPlatformFSComponent : SharedPlatformFSComponent {
  override fun provideFSAppDirs(): FSAppDirs {
    return DesktopFSAppDirs()
  }
}
