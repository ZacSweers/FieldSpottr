//
//  ContentView.swift
//  FieldSpottr
//
//  Created by Zac Sweers on 6/13/24.
//

import SwiftUI
import FieldSpottrKt

struct ContentView: View {
    private let component: FSComponent

    init() {
        self.component = FSComponent(shared: IosSharedPlatformFSComponent())
    }

    var body: some View {
        ComposeView(component: self.component)
            .ignoresSafeArea(.all, edges: .all)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    private let component: FSComponent

    init(component: FSComponent) {
        self.component = component
    }

    func makeUIViewController(context _: Context) -> UIViewController {
        return FSUiViewControllerKt.makeUiViewController(component: component)
    }

    func updateUIViewController(_: UIViewController, context _: Context) {}
}

#Preview {
    ContentView()
}
