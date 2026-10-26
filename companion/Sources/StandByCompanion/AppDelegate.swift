import AppKit
import Combine
import SwiftUI
import UniformTypeIdentifiers

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var statusItem: NSStatusItem!
    let conn = Connection()
    private(set) lazy var hub = Hub(conn: conn)
    private var bag = Set<AnyCancellable>()
    private var windows: [String: NSWindow] = [:]

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
        conn.$status.receive(on: RunLoop.main).sink { [weak self] _ in self?.updateIcon() }.store(in: &bag)
        updateIcon()
        hub.start()
        conn.start()
        Log.i("StandBy Companion started; adb: \(ADB.path ?? "not found")")
        // `open -a "StandBy Companion" --args --settings` (or --logs) opens a window right away; handy for testing
        let args = CommandLine.arguments
        if args.contains("--settings") { openSettings() }
        if args.contains("--logs") { openLogs() }
        // `--snapshot <dir>`: render the open windows to PNG (used for the README screenshots)
        if let i = args.firstIndex(of: "--snapshot"), i + 1 < args.count {
            let dir = URL(fileURLWithPath: args[i + 1])
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { self.snapshot(to: dir) }
        }
    }

    private func updateIcon() {
        let connected = conn.status.isConnected
        let name = connected ? "iphone.landscape" : "iphone.slash"
        let img = NSImage(systemSymbolName: name, accessibilityDescription: "StandBy") ?? NSImage(systemSymbolName: "clock", accessibilityDescription: nil)
        img?.isTemplate = true
        statusItem.button?.image = img
        statusItem.button?.toolTip = "StandBy Companion — \(conn.status.text)"
    }

    // MARK: menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let st = NSMenuItem(title: conn.status.text, action: nil, keyEquivalent: "")
        st.image = NSImage(systemSymbolName: conn.status.isConnected ? "circle.fill" : "circle", accessibilityDescription: nil)
        st.isEnabled = false
        menu.addItem(st)
        menu.addItem(.separator())
        menu.addItem(item("Check Connection", #selector(checkConnection), "checkmark.circle"))
        menu.addItem(item("Open StandBy on Phone", #selector(openLauncher), "iphone"))
        menu.addItem(item("Send Theme…", #selector(sendTheme), "paintpalette"))
        menu.addItem(.separator())
        menu.addItem(item("Settings…", #selector(openSettings), "gearshape", key: ","))
        menu.addItem(item("Logs…", #selector(openLogs), "doc.text.magnifyingglass", key: "l"))
        menu.addItem(.separator())
        menu.addItem(item("Quit StandBy Companion", #selector(quit), nil, key: "q"))
    }

    private func item(_ title: String, _ sel: Selector, _ symbol: String?, key: String = "") -> NSMenuItem {
        let i = NSMenuItem(title: title, action: sel, keyEquivalent: key)
        i.target = self
        if let symbol { i.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil) }
        return i
    }

    @objc func checkConnection() {
        ConnectionCheck.run(conn: conn) { report in
            let a = NSAlert()
            a.messageText = report.ok ? "Connection OK" : "Connection problem"
            a.informativeText = report.lines.joined(separator: "\n")
            a.alertStyle = report.ok ? .informational : .warning
            NSApp.activate(ignoringOtherApps: true)
            a.runModal()
        }
    }

    @objc func openLauncher() {
        DispatchQueue.global().async {
            guard let d = ADB.pick() else { return Log.w("No device") }
            let r = ADB.run(["-s", d.serial, "shell", "am", "start", "-n", "dev.standby/.MainActivity"])
            Log.i(r.status == 0 ? "Launcher started on phone" : "Could not start launcher: \(r.out)")
        }
    }

    @objc func sendTheme() {
        let p = NSOpenPanel()
        p.allowedContentTypes = [.json]
        p.message = "Choose a StandBy theme (.json)"
        NSApp.activate(ignoringOtherApps: true)
        guard p.runModal() == .OK, let url = p.url else { return }
        if !conn.status.isConnected {
            alert("Phone is not connected", "Connect the phone first.")
        } else if let err = hub.sendTheme(url) {
            alert("Can't send theme", err)
        }
    }

    private func alert(_ title: String, _ text: String) {
        let a = NSAlert()
        a.messageText = title
        a.informativeText = text
        a.runModal()
    }

    @objc func openSettings() {
        show("settings", title: "StandBy Companion Settings", view: SettingsView(conn: conn), size: NSSize(width: 480, height: 560))
    }

    @objc func openLogs() {
        show("logs", title: "StandBy Logs", view: LogsView(), size: NSSize(width: 720, height: 460), resizable: true)
    }

    @objc func quit() { NSApp.terminate(nil) }

    private func snapshot(to dir: URL) {
        // show the whole settings form, not just the part that fits the default window
        windows["settings"]?.setContentSize(NSSize(width: 480, height: 1180))
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.renderWindows(to: dir) }
    }

    private func renderWindows(to dir: URL) {
        for (key, w) in windows {
            guard let v = w.contentView, let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) else { continue }
            v.cacheDisplay(in: v.bounds, to: rep)
            let url = dir.appendingPathComponent("companion-\(key).png")
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
            Log.i("Snapshot saved: \(url.path)")
        }
    }

    private func show<V: View>(_ key: String, title: String, view: V, size: NSSize, resizable: Bool = false) {
        if let w = windows[key] {
            NSApp.activate(ignoringOtherApps: true)
            w.makeKeyAndOrderFront(nil)
            return
        }
        var style: NSWindow.StyleMask = [.titled, .closable, .miniaturizable]
        if resizable { style.insert(.resizable) }
        let w = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: style, backing: .buffered, defer: false)
        w.title = title
        w.contentViewController = NSHostingController(rootView: view)
        w.setContentSize(size)
        w.isReleasedWhenClosed = false
        w.center()
        windows[key] = w
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }
}

/// Step-by-step diagnostics used by the menu and the settings window.
enum ConnectionCheck {
    struct Report {
        var ok = true
        var lines: [String] = []
    }

    static func run(conn: Connection, done: @escaping (Report) -> Void) {
        DispatchQueue.global().async {
            var r = Report()
            guard let adb = ADB.path else {
                r.ok = false
                r.lines.append("✗ adb not found. Install Android platform-tools (brew install android-platform-tools) or set the path in Settings.")
                return DispatchQueue.main.async { done(r) }
            }
            r.lines.append("✓ adb: \(adb)")
            let devs = ADB.devices()
            if devs.isEmpty {
                r.ok = false
                r.lines.append("✗ No Android device. Connect it via USB and enable USB debugging.")
                return DispatchQueue.main.async { done(r) }
            }
            for d in devs { r.lines.append("\(d.state == "device" ? "✓" : "✗") \(d.model.isEmpty ? d.serial : d.model) — \(d.state)") }
            guard let d = ADB.pick() else {
                r.ok = false
                r.lines.append("✗ No authorised device (accept the USB debugging prompt on the phone).")
                return DispatchQueue.main.async { done(r) }
            }
            let pm = ADB.run(["-s", d.serial, "shell", "pm", "path", "dev.standby"], timeout: 5)
            if !pm.out.contains("package:") {
                r.ok = false
                r.lines.append("✗ StandBy launcher is not installed on the phone.")
                return DispatchQueue.main.async { done(r) }
            }
            r.lines.append("✓ StandBy launcher installed")
            DispatchQueue.main.async {
                conn.ping { ms in
                    if let ms {
                        r.lines.append(String(format: "✓ Launcher responds (%.0f ms)", ms))
                    } else {
                        r.ok = false
                        r.lines.append("✗ Launcher does not respond on port \(Settings.shared.port). Open StandBy on the phone and check the port in its settings.")
                    }
                    done(r)
                }
            }
        }
    }
}
