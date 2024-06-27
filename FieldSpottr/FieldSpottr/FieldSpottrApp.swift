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
        if let key = BuildConfig.shared.BUGSNAG_NOTIFIER_KEY {
            Bugsnag.start(withApiKey: key)
            let config = BugsnagConfiguration.loadConfig()
            BugsnagConfigKt.startBugsnag(config: config)
        }
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
