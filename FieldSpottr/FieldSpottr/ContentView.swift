//
//  ContentView.swift
//  FieldSpottr
//
//  Created by Zac Sweers on 6/13/24.
//

import SwiftUI
import FieldSpottrKt

struct ContentView: View {
    private let graph: FSGraph

    init() {
        self.graph = IosFSGraphCompanion.shared.create()
    }

    var body: some View {
        ComposeView(graph: self.graph)
            .ignoresSafeArea(.all, edges: .all)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    private let graph: FSGraph

    init(graph: FSGraph) {
        self.graph = graph
    }

    func makeUIViewController(context _: Context) -> UIViewController {
        return FSUiViewControllerKt.makeUiViewController(graph: graph)
    }

    func updateUIViewController(_: UIViewController, context _: Context) {}
}

#Preview {
    ContentView()
}
