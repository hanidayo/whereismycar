import SwiftUI
import MapKit

struct ParkingDetailView: View {
    let recordID: UUID
    @EnvironmentObject var store: ParkingStore
    @Environment(\.openURL) private var openURL
    @State private var showCamera = false
    @State private var analyzing = false
    @State private var editingPlace = false

    var body: some View {
        if let r = store.record(recordID) {
            let color = CarPalette.color(store.carColor(r.carID))
            ScrollView {
                VStack(spacing: 14) {
                    hero(r, color: color)
                    if r.coordinate != nil { placeCard(r) }
                    if let c = r.coordinate { mapCard(r, coordinate: c, color: color) }
                    photoCard(r, color: color)
                    infoCard(r)
                }
                .padding(16)
            }
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle(store.carName(r.carID))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Section("다른 차로 바꾸기") {
                            ForEach(store.cars) { car in
                                Button {
                                    store.changeCar(recordID, to: car.id)
                                } label: {
                                    if car.id == r.carID { Label(car.name, systemImage: "checkmark") } else { Text(car.name) }
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "car.2")
                    }
                }
            }
            .task { await store.fetchRemotePhoto(recordID) }
            .sheet(isPresented: $editingPlace) {
                let g = store.place(for: r)
                PlaceEditSheet(recordID: recordID, name: g?.name, icon: g?.icon)
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { image in
                    Task {
                        analyzing = true
                        await store.attachPhoto(image, to: recordID)
                        analyzing = false
                    }
                }
                .ignoresSafeArea()
            }
        } else {
            ContentUnavailableView("기록 없음", systemImage: "car")
        }
    }

    private func placeCard(_ r: ParkingRecord) -> some View {
        let g = store.place(for: r)
        return Button { editingPlace = true } label: {
            HStack(spacing: 12) {
                PlaceBadge(place: PlaceIcon.resolve(icon: g?.icon, name: g?.name), size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text("장소").font(.caption).foregroundStyle(Palette.sub)
                    Text(g?.name ?? "장소 이름 붙이기")
                        .font(.system(.headline, design: .rounded))
                        .foregroundStyle(g?.name == nil ? Palette.primary : Palette.ink)
                }
                Spacer()
                Image(systemName: "pencil").foregroundStyle(Palette.sub)
            }
            .card(padding: 14)
        }
        .buttonStyle(.plain)
    }

    private func hero(_ r: ParkingRecord, color: Color) -> some View {
        VStack(spacing: 12) {
            FloorTile(level: r.level, color: color, size: 128)
            HStack(spacing: 6) {
                if r.calibrated { Chip(text: "보정됨", systemImage: "checkmark", color: .green) }
                if let p = r.pillarText { Chip(text: p, systemImage: "square.grid.2x2", color: color) }
            }
            Text("\(r.member) · \(r.date.formatted(date: .abbreviated, time: .shortened))")
                .font(.subheadline).foregroundStyle(.secondary)

            HStack(spacing: 10) {
                Menu {
                    ForEach(Array((-6...4).reversed()), id: \.self) { i in
                        Button(Level.name(i)) { store.correct(recordID, to: i) }
                    }
                } label: {
                    Label("층 보정", systemImage: "slider.vertical.3").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                if let ocr = r.ocrLevel, ocr != r.level {
                    Button { store.correct(recordID, to: ocr) } label: {
                        Label("사진대로 \(Level.name(ocr))", systemImage: "text.viewfinder").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).tint(color)
                }
            }
            .controlSize(.large)
            Text("보정하면 이 주차장의 층 높이를 학습해서 다음부터 더 정확해져요.")
                .font(.caption).foregroundStyle(.tertiary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .card()
    }

    private func mapCard(_ r: ParkingRecord, coordinate c: CLLocationCoordinate2D, color: Color) -> some View {
        VStack(spacing: 0) {
            Map(initialPosition: .region(MKCoordinateRegion(center: c, latitudinalMeters: 350, longitudinalMeters: 350))) {
                Marker(store.carName(r.carID), systemImage: "car.fill", coordinate: c).tint(color)
            }
            .frame(height: 200)
            .allowsHitTesting(false)

            Button {
                let name = "\(store.carName(r.carID)) \(Level.longName(r.level))"
                if let url = NaverMap.walkURL(lat: c.latitude, lon: c.longitude, name: name) {
                    // 네이버 지도가 없으면 앱스토어로
                    openURL(url) { ok in if !ok { openURL(NaverMap.appStore) } }
                }
            } label: {
                HStack {
                    Label("네이버 지도로 길찾기", systemImage: "figure.walk")
                    Spacer()
                    Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                }
                .padding(16)
            }
        }
        .background(Palette.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func photoCard(_ r: ParkingRecord, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("기둥 사진").font(.headline)
            if let img = store.photo(for: r) {
                Image(uiImage: img).resizable().scaledToFill()
                    .frame(maxWidth: .infinity).frame(height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else if r.hasRemotePhoto {
                ProgressView("사진 받는 중…").frame(maxWidth: .infinity, minHeight: 80)
            }
            if analyzing { ProgressView("글자 읽는 중…") }
            Button { showCamera = true } label: {
                Label(r.photoFile == nil ? "기둥 사진 찍기" : "다시 찍기", systemImage: "camera.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered).controlSize(.large).tint(color)
        }
        .card()
    }

    private func infoCard(_ r: ParkingRecord) -> some View {
        VStack(spacing: 10) {
            if let d = r.deltaMeters { row("입구 대비 높이", String(format: "%+.1fm", d)) }
            if let a = r.accuracy { row("GPS 오차", String(format: "약 %.0fm", a)) }
            row("기록한 사람", r.member)
            row("공유", r.synced ? "가족에게 공유됨" : (store.sync.isConfigured ? "올리는 중" : "공유 꺼짐"))
        }
        .font(.subheadline)
        .card()
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k).foregroundStyle(.secondary); Spacer(); Text(v).monospacedDigit() }
    }
}

struct CameraPicker: UIViewControllerRepresentable {
    var onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onImage(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
