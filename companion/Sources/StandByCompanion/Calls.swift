import Accelerate
import AppKit
import AVFoundation

/// Detects calls (an app capturing the microphone) and toggles mic / output mute system-wide.
final class CallMonitor {
    private var since: Date?
    private var lastApp = ""
    /// Set while our own visualizer captures audio, so we don't count it as a call (pre-14.2 fallback).
    var ownCapture = false
    private(set) var lastActive = false

    private let knownApps: [String: String] = [
        "us.zoom.xos": "Zoom", "com.microsoft.teams2": "Microsoft Teams", "com.microsoft.teams": "Microsoft Teams",
        "com.apple.FaceTime": "FaceTime", "com.hnc.Discord": "Discord", "ru.keepcoder.Telegram": "Telegram",
        "org.telegram.desktop": "Telegram", "com.tinyspeck.slackmacgap": "Slack", "com.skype.skype": "Skype",
        "com.cisco.webexmeetingsapp": "Webex", "com.google.Chrome": "Google Chrome", "com.apple.Safari": "Safari",
        "org.mozilla.firefox": "Firefox", "company.thebrowser.Browser": "Arc", "ru.yandex.desktop.telemost": "Telemost",
        "com.apple.WebKit.GPU": "Safari",
    ]

    private func appName(pid: pid_t, bundle: String) -> String {
        if let n = knownApps[bundle] { return n }
        if let k = knownApps.keys.first(where: { bundle.hasPrefix($0) }) { return knownApps[k]! }
        if let app = NSRunningApplication(processIdentifier: pid), let n = app.localizedName {
            return n.replacingOccurrences(of: " Helper", with: "").replacingOccurrences(of: " (Renderer)", with: "")
        }
        return bundle
    }

    func status() -> [String: Any] {
        var active: Bool
        var app = ""
        if let procs = Audio.inputProcesses() {
            active = !procs.isEmpty
            if let p = procs.first { app = appName(pid: p.pid, bundle: p.bundle) }
        } else {
            active = ownCapture ? lastActive : Audio.isRunningSomewhere(input: true)
        }
        lastActive = active
        if active && since == nil { since = Date() }
        if !active { since = nil }
        if active && !app.isEmpty && app != lastApp { Log.i("Microphone in use by \(app)") }
        lastApp = app
        return [
            "type": "call", "active": active, "app": app,
            "since": since.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0,
            "micMuted": Audio.mute(input: true) ?? false,
            "outputMuted": Audio.mute(input: false) ?? false,
            "input": Audio.name(Audio.defaultDevice(input: true)),
            "output": Audio.name(Audio.defaultDevice(input: false)),
        ]
    }

    func command(_ action: String, value: Any?) {
        let on = value as? Bool
        switch action {
        case "call.mic":
            let target = on ?? !(Audio.mute(input: true) ?? false)
            Audio.setMute(input: true, target)
            Log.i("Microphone \(target ? "muted" : "unmuted")")
        case "call.output":
            let target = on ?? !(Audio.mute(input: false) ?? false)
            Audio.setMute(input: false, target)
            Log.i("Sound \(target ? "muted" : "unmuted")")
        default: break
        }
    }
}

