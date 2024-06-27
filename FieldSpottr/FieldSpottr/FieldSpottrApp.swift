//
//  FieldSpottrApp.swift
//  FieldSpottr
//
//  Created by Zac Sweers on 6/13/24.
//

import SwiftUI
import Bugsnag
import FieldSpottrKt

@main
struct FieldSpottrApp: App {
    init() {
        if BuildConfig.shared.IS_RELEASE {
            if let key = BuildConfig.shared.BUGSNAG_NOTIFIER_KEY {
                let config = BugsnagConfiguration(_: key)
                BugsnagConfigKt.startBugsnag(config: config)
                BugsnagKotlinKt.enableBugsnag()
            }
        }
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
