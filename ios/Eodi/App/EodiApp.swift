import SwiftUI

@main
struct EodiApp: App {
    @StateObject private var store = ParkingStore.shared
    @StateObject private var recorder = DriveRecorder.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(recorder)
                .onAppear {
                    recorder.requestPermissions()
                    Notifier.requestAuthorization()
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { Task { await store.refresh() } }
                }
        }
    }
}
