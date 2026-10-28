import AppKit
import SwiftUI

struct SettingsView: View {
    @ObservedObject var conn: Connection
    @ObservedObject private var s = Settings.shared
    @State private var devices: [ADB.Device] = []
    @State private var check: String = ""
    @State private var checking = false
    @State private var portText = String(Settings.shared.port)
    @State private var loginItem = Settings.shared.launchAtLogin
    @State private var wifiBusy = false
    @State private var wifiResult = ""

    var body: some View {
        Form {
            Section("Connection") {
                LabeledContent("Status") {
                    HStack(spacing: 6) {
                        Circle().fill(conn.status.isConnected ? Color.green : Color.orange).frame(width: 8, height: 8)
                        Text(conn.status.text).foregroundStyle(.secondary)
                    }
                }
                if let ms = conn.latency {
                    LabeledContent("Latency", value: String(format: "%.0f ms", ms))
                }
                HStack {
                    TextField("adb path", text: $s.adbPath, prompt: Text(ADB.detect() ?? "/opt/homebrew/bin/adb"))
                    Button("Detect") { s.adbPath = ADB.detect() ?? "" }
                }
                Picker("Device", selection: $s.serial) {
                    Text("Automatic").tag("")
                    ForEach(devices, id: \.serial) { d in
                        Text("\(d.model.isEmpty ? d.serial : d.model) (\(d.state))").tag(d.serial)
                    }
                }
                HStack {
                    TextField("Port", text: $portText)
                        .onSubmit(applyPort)
                    Button("Apply", action: applyPort)
                }
                HStack {
                    Button(checking ? "Checking…" : "Test Connection") {
                        checking = true
                        check = ""
                        ConnectionCheck.run(conn: conn) { r in
                            check = r.lines.joined(separator: "\n")
                            checking = false
                        }
                    }.disabled(checking)
                    Button("Refresh Devices", action: refreshDevices)
                }
                if !check.isEmpty {
                    Text(check).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                }
            }
            Section {
                Toggle("Connect over Wi-Fi", isOn: $s.wifiEnabled)
                    .disabled(s.wifiAddress.isEmpty)
                LabeledContent("Phone address", value: s.wifiAddress.isEmpty ? "not set up" : s.wifiAddress)
                HStack {
                    Button(wifiBusy ? "Setting Up…" : "Set Up Over USB", action: setUpWifi).disabled(wifiBusy)
                    Button("Back to USB Only", action: disableWifi).disabled(wifiBusy || s.wifiAddress.isEmpty)
                }
                if !wifiResult.isEmpty {
                    Text(wifiResult).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                }
            } header: {
                Text("Wi-Fi Debugging")
            } footer: {
                Text("Connect the phone by USB once and press “Set Up Over USB”. After that the cable can be unplugged; if the phone restarts or gets a new IP, plug it in again and the companion repairs Wi-Fi automatically. Reserve the phone's IP in your router for best results.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Data sent to the phone") {
                Toggle("System stats (CPU, GPU, memory, network)", isOn: $s.sendStats)
                Toggle("Now playing (Music, Spotify)", isOn: $s.sendMedia)
                Toggle("Calls (microphone and sound)", isOn: $s.sendCall)
                Toggle("Microphone visualizer", isOn: $s.visualizer)
                Toggle("Touch Bar emulator (needs Accessibility for taps)", isOn: $s.touchBar)
            }
            Section("Phone") {
                HStack {
                    Button("Keep Awake While Charging") { stayOn(true) }
                    Button("Restore Default") { stayOn(false) }
                }
                Button("Open StandBy on Phone") {
                    (NSApp.delegate as? AppDelegate)?.openLauncher()
                }
            }
            Section("General") {
                Toggle("Launch at login", isOn: Binding(get: { loginItem }, set: { s.launchAtLogin = $0; loginItem = s.launchAtLogin }))
            }
        }
        .formStyle(.grouped)
        .onAppear(perform: refreshDevices)
    }

    private func setUpWifi() {
        wifiBusy = true
        wifiResult = ""
        DispatchQueue.global().async {
            let usb = ADB.devices().first { !$0.isNetwork && $0.state == "device" }
            let r: Result<String, ADBError> = usb.map { ADB.enableWifi(usbSerial: $0.serial) } ?? .failure(ADBError("Connect the phone by USB first."))
            DispatchQueue.main.async {
                wifiBusy = false
                switch r {
                case .success(let addr):
                    s.wifiAddress = addr
                    s.wifiEnabled = true
                    wifiResult = "Wi-Fi debugging is on: \(addr). You can unplug the cable."
                    Log.i("Wi-Fi debugging enabled: \(addr)")
                case .failure(let e):
                    wifiResult = e.description
                    Log.w("Wi-Fi setup: \(e.description)")
                }
            }
        }
    }

    private func disableWifi() {
        let addr = s.wifiAddress
        s.wifiEnabled = false
        s.wifiAddress = ""
        wifiResult = "Switched back to USB."
        DispatchQueue.global().async {
            ADB.disableWifi(addr)
            Log.i("Wi-Fi debugging disabled")
            conn.restart()
        }
    }

    private func applyPort() {
        if let p = Int(portText), (1024...65535).contains(p) {
            s.port = p
            conn.restart()
        } else {
            portText = String(s.port)
        }
    }

    private func refreshDevices() {
        DispatchQueue.global().async {
            let d = ADB.devices()
            DispatchQueue.main.async { devices = d }
        }
    }

    private func stayOn(_ on: Bool) {
        DispatchQueue.global().async {
            guard let d = ADB.pick() else { return Log.w("No device") }
            let r = ADB.run(["-s", d.serial, "shell", "settings", "put", "global", "stay_on_while_plugged_in", on ? "7" : "0"])
            Log.i(r.status == 0 ? "Stay awake while charging: \(on ? "on" : "off")" : "Failed: \(r.out)")
        }
    }
}

struct LogsView: View {
    @ObservedObject private var log = Log.shared
    @State private var source = 0
    @State private var phoneLog = ""
    @State private var follow = true
    private let timer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Picker("", selection: $source) {
                    Text("Companion").tag(0)
                    Text("Launcher (phone)").tag(1)
                }
                .pickerStyle(.segmented)
                .frame(width: 280)
                Spacer()
                Toggle("Follow", isOn: $follow).toggleStyle(.checkbox)
                Button("Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(source == 0 ? log.text() : phoneLog, forType: .string)
                }
                Button("Clear") {
                    if source == 0 { log.clear() } else {
                        phoneLog = ""
                        DispatchQueue.global().async {
                            if let d = ADB.pick() { ADB.run(["-s", d.serial, "logcat", "-c"]) }
                        }
                    }
                }
            }
            .padding(10)
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    if source == 0 {
                        LazyVStack(alignment: .leading, spacing: 2) {
                            ForEach(log.entries) { e in
                                Text(log.format(e))
                                    .foregroundStyle(e.level == "W" ? Color.orange : Color.primary)
                                    .id(e.id)
                            }
                        }
                        .padding(10)
                    } else {
                        Text(phoneLog.isEmpty ? "No launcher log yet." : phoneLog)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(10)
                            .id(-1)
                    }
                    Color.clear.frame(height: 1).id(-2)
                }
                .font(.system(size: 11, design: .monospaced))
                .textSelection(.enabled)
                .onChange(of: log.entries.count) { _ in if follow && source == 0 { proxy.scrollTo(-2) } }
                .onChange(of: phoneLog) { _ in if follow && source == 1 { proxy.scrollTo(-2) } }
            }
        }
        .onReceive(timer) { _ in if source == 1 { fetchPhone() } }
        .onChange(of: source) { s in if s == 1 { fetchPhone() } }
    }

    private func fetchPhone() {
        DispatchQueue.global().async {
            guard let d = ADB.pick() else {
                DispatchQueue.main.async { phoneLog = "No device connected." }
                return
            }
            let r = ADB.run(["-s", d.serial, "logcat", "-d", "-v", "time", "-t", "400", "-s", "StandBy:*"], timeout: 5)
            let text = r.out.split(separator: "\n").filter { !$0.hasPrefix("---------") }.joined(separator: "\n")
            DispatchQueue.main.async { if text != phoneLog { phoneLog = text } }
        }
    }
}
