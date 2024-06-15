package dev.zacsweers.fieldspottr.di

import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.data.NSFileManagerFSAppDirs

class IosSharedPlatformFSComponent : SharedPlatformFSComponent {
  override fun provideFSAppDirs(): FSAppDirs {
    return NSFileManagerFSAppDirs()
  }
}