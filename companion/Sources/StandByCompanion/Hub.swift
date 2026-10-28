import AppKit

/// Wires collectors to the connection: periodic updates out, commands in.
final class Hub {
    let conn: Connection
    private let settings = Settings.shared
    private let stats = SystemStats()
    private let media = MediaController()
    private let calls = CallMonitor()
    private let viz = Visualizer()
    private var timer: Timer?
    private var vizTimer: Timer?
    private var wantViz = false
    private let touchBar = TouchBarEmulator()
    private var wantTouchBar = false
    /// The mic is added only during a call: opening it earlier would switch Bluetooth headsets to the low-quality headset profile.
    private var callActive = false
    private let statsQ = DispatchQueue(label: "stats")
    private let allowedHosts: Set<String> = ["api.open-meteo.com", "geocoding-api.open-meteo.com"]

    init(conn: Connection) {
        self.conn = conn
        conn.onMessage = { [weak self] in self?.handle($0) }
        conn.onConnected = { [weak self] in
            self?.media.resetArtwork()
            self?.tick()
        }
        media.onArtwork = { [weak self] id, b64 in
            self?.conn.send(["type": "artwork", "id": id, "data": b64])
        }
        touchBar.onFrame = { [weak self] png, w, h, size in
            guard let self else { return }
            let app = DispatchQueue.main.sync { NSWorkspace.shared.frontmostApplication?.localizedName ?? "" }
            self.conn.send([
                "type": "touchbar", "data": png.base64EncodedString(), "w": w, "h": h,
                "pw": Double(size.width), "ph": Double(size.height), "app": app,
                "input": self.touchBar.inputAllowed,
            ])
        }
    }

    /// Restores the system state we changed (the virtual Touch Bar).
    func shutdown() { touchBar.stop() }

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
    }

    private func tick() {
        guard conn.status.isConnected else {
            updateViz()
            updateTouchBar()
            return
        }
        updateTouchBar()
        if settings.sendStats {
            statsQ.async {
                let s = self.stats.sample()
                self.conn.send(s)
            }
        }
        if settings.sendMedia {
            media.poll { [weak self] msg in self?.conn.send(msg) }
        }
        if settings.sendCall {
            statsQ.async {
                let st = self.calls.status()
                self.conn.send(st)
                let active = st["active"] as? Bool ?? false
                DispatchQueue.main.async {
                    self.callActive = active
                    self.updateViz()
                }
            }
        } else {
            updateViz()
        }
    }

    private func updateViz() {
        let on = wantViz && settings.visualizer && settings.sendCall && conn.status.isConnected
        viz.update(on: on, withMic: callActive)
        calls.ownCapture = viz.mic.running
        if on && vizTimer == nil {
            vizTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
                guard let self, self.viz.running else { return }
                let b = self.viz.snapshot().map { (Double($0) * 1000).rounded() / 1000 }
                self.conn.send(["type": "viz", "bands": b])
            }
        } else if !on, let t = vizTimer {
            t.invalidate()
            vizTimer = nil
        }
    }

    private func updateTouchBar() {
        let on = wantTouchBar && settings.touchBar && conn.status.isConnected
        if on && !touchBar.running {
            touchBar.start()
        } else if !on && touchBar.running {
            touchBar.stop()
        }
    }

    private func handle(_ o: [String: Any]) {
        switch o["type"] as? String {
        case "cmd":
            let action = o["action"] as? String ?? ""
            Log.i("Command from phone: \(action)\(o["value"].map { " \($0)" } ?? "")")
            if action.hasPrefix("media.") || action == "volume.set" {
                media.command(action, value: (o["value"] as? NSNumber).map { $0.doubleValue } ?? o["value"])
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.media.poll { [weak self] in self?.conn.send($0) } }
            } else if action.hasPrefix("call.") {
                calls.command(action, value: o["value"])
                statsQ.asyncAfter(deadline: .now() + 0.2) { self.conn.send(self.calls.status()) }
            }
        case "subscribe":
            // each key is optional: only the subscriptions present in the message change
            if let v = o["viz"] as? Bool {
                wantViz = v
                updateViz()
            }
            if let t = o["touchbar"] as? Bool {
                wantTouchBar = t
                updateTouchBar()
                if t { touchBar.resend() }
            }
        case "touchbar.touch":
            guard let ph = (o["phase"] as? String).flatMap(TouchBarEmulator.Phase.init(rawValue:)),
                  let x = (o["x"] as? NSNumber)?.doubleValue, let y = (o["y"] as? NSNumber)?.doubleValue else { return }
            touchBar.touch(ph, at: CGPoint(x: x, y: y))
        case "http":
            proxy(o)
        case "ack":
            Log.i("Phone: \(o["what"] as? String ?? "") \((o["ok"] as? Bool ?? false) ? "ok" : "failed: \(o["error"] ?? "")")")
        default: break
        }
    }

    /// Weather requests for a phone without internet access (open-meteo hosts only).
    private func proxy(_ o: [String: Any]) {
        guard let id = o["id"], let s = o["url"] as? String, let url = URL(string: s), let host = url.host,
              allowedHosts.contains(host) else {
            conn.send(["type": "http", "id": o["id"] ?? 0, "status": 403, "body": ""])
            return
        }
        Log.i("Proxying request to \(host)")
        URLSession.shared.dataTask(with: url) { data, resp, err in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if let err { Log.w("Proxy: \(err.localizedDescription)") }
            self.conn.send(["type": "http", "id": id, "status": code, "body": data.flatMap { String(data: $0, encoding: .utf8) } ?? ""])
        }.resume()
    }

    /// Sends a theme JSON file to the launcher.
    func sendTheme(_ url: URL) -> String? {
        guard let data = try? Data(contentsOf: url), let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return "Not a valid JSON file"
        }
        guard obj["light"] != nil || obj["dark"] != nil else { return "Theme needs a \"light\" or \"dark\" object" }
        conn.send(["type": "theme", "theme": obj, "file": url.deletingPathExtension().lastPathComponent])
        Log.i("Theme sent: \(url.lastPathComponent)")
        return nil
    }
}
