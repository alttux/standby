import AppKit

/// Now-playing info and transport control for Apple Music and Spotify (via AppleScript).
final class MediaController {
    private struct Player {
        let bundle: String
        let app: String
        let script: String
    }

    private let players = [
        Player(bundle: "com.spotify.client", app: "Spotify", script: "Spotify"),
        Player(bundle: "com.apple.Music", app: "Music", script: "Music"),
    ]

    private let q = DispatchQueue(label: "media")
    private var lastError = ""
    private var active: Player?
    private(set) var trackId = ""
    private var artworkFor = ""
    var onArtwork: ((String, String) -> Void)?   // (trackId, base64 jpeg)

    private func running(_ p: Player) -> Bool {
        !NSRunningApplication.runningApplications(withBundleIdentifier: p.bundle).isEmpty
    }

    private func run(_ src: String) -> String? {
        var err: NSDictionary?
        let r = NSAppleScript(source: src)?.executeAndReturnError(&err)
        if let err {
            let n = err[NSAppleScript.errorNumber] as? Int ?? 0
            let msg = n == -1743 ? "Automation permission denied: allow it in System Settings → Privacy & Security → Automation"
                : "AppleScript error \(n): \(err[NSAppleScript.errorMessage] ?? "")"
            // log each distinct error once, polling runs every second
            if msg != lastError { Log.w(msg) }
            lastError = msg
            return nil
        }
        lastError = ""
        return r?.stringValue
    }

    private func num(_ s: String) -> Double { Double(s.replacingOccurrences(of: ",", with: ".")) ?? 0 }

    /// Polls the players; returns the message for the launcher.
    func poll(completion: @escaping ([String: Any]) -> Void) {
        q.async {
            var best: [String: Any]?
            for p in self.players where self.running(p) {
                let artwork = p.script == "Spotify" ? "(artwork url of t)" : "\"\""
                let idExpr = p.script == "Spotify" ? "(id of t)" : "((persistent ID of t) as string)"
                let src = """
                tell application "\(p.script)"
                    set pstate to player state as string
                    if pstate is "stopped" then return "stopped"
                    set t to current track
                    return pstate & tab & (name of t) & tab & (artist of t) & tab & (album of t) & tab & ((duration of t) as string) & tab & ((player position) as string) & tab & \(artwork) & tab & \(idExpr)
                end tell
                """
                guard let out = self.run(src), out != "stopped" else { continue }
                let f = out.components(separatedBy: "\t")
                guard f.count >= 8 else { continue }
                var duration = self.num(f[4])
                if p.script == "Spotify" { duration /= 1000 }
                let playing = f[0] == "playing"
                let msg: [String: Any] = [
                    "type": "media", "available": true, "app": p.app, "title": f[1], "artist": f[2], "album": f[3],
                    "duration": duration, "position": self.num(f[5]), "playing": playing,
                    "artworkId": f[7], "artworkUrl": f[6],
                ]
                if best == nil || playing { best = msg; self.active = p }
                if playing { break }
            }
            var msg = best ?? ["type": "media", "available": false]
            msg["volume"] = Double(Audio.volume(input: false) ?? 0.5)
            if let id = msg["artworkId"] as? String, !id.isEmpty {
                self.trackId = id
                if id != self.artworkFor {
                    self.artworkFor = id
                    self.loadArtwork(id: id, url: msg["artworkUrl"] as? String ?? "")
                }
            }
            msg.removeValue(forKey: "artworkUrl")
            DispatchQueue.main.async { completion(msg) }
        }
    }

    /// Re-sends artwork on the next poll (after a reconnect).
    func resetArtwork() { q.async { self.artworkFor = "" } }

    private func loadArtwork(id: String, url: String) {
        if !url.isEmpty, let u = URL(string: url) {
            URLSession.shared.dataTask(with: u) { data, _, _ in
                if let data, let img = NSImage(data: data) { self.emitArtwork(id, img) }
            }.resume()
        } else if active?.script == "Music" {
            let path = NSTemporaryDirectory() + "standby-artwork"
            let src = """
            tell application "Music"
                try
                    set d to raw data of artwork 1 of current track
                    set fh to open for access (POSIX file "\(path)") with write permission
                    set eof fh to 0
                    write d to fh
                    close access fh
                    return "ok"
                on error
                    try
                        close access (POSIX file "\(path)")
                    end try
                    return "none"
                end try
            end tell
            """
            if run(src) == "ok", let img = NSImage(contentsOfFile: path) { emitArtwork(id, img) }
        }
    }

    private func emitArtwork(_ id: String, _ img: NSImage) {
        let size = NSSize(width: 320, height: 320)
        let out = NSImage(size: size)
        out.lockFocus()
        NSGraphicsContext.current?.imageInterpolation = .high
        img.draw(in: NSRect(origin: .zero, size: size), from: .zero, operation: .copy, fraction: 1)
        out.unlockFocus()
        guard let tiff = out.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let jpg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.8]) else { return }
        let b64 = jpg.base64EncodedString()
        DispatchQueue.main.async { self.onArtwork?(id, b64) }
    }

    func command(_ action: String, value: Any?) {
        q.async {
            let p = self.active.flatMap { self.running($0) ? $0 : nil } ?? self.players.first { self.running($0) }
            switch action {
            case "media.playpause", "media.next", "media.prev":
                if let p {
                    let verb = ["media.playpause": "playpause", "media.next": "next track", "media.prev": "previous track"][action]!
                    _ = self.run("tell application \"\(p.script)\" to \(verb)")
                } else {
                    // no supported player: emulate the hardware media keys
                    let key: Int32 = action == "media.playpause" ? 16 : action == "media.next" ? 17 : 18
                    DispatchQueue.main.async { Self.mediaKey(key) }
                }
            case "media.seek":
                if let p, let v = value as? Double {
                    _ = self.run("tell application \"\(p.script)\" to set player position to \(String(format: "%.2f", v))")
                }
            case "volume.set":
                if let v = value as? Double { Audio.setVolume(input: false, Float(v)) }
            default: break
            }
        }
    }

    private static func mediaKey(_ key: Int32) {
        for down in [true, false] {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xA00 : 0xB00)
            let data1 = Int((key << 16) | ((down ? 0xA : 0xB) << 8))
            let ev = NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: flags, timestamp: 0,
                                        windowNumber: 0, context: nil, subtype: 8, data1: data1, data2: -1)
            ev?.cgEvent?.post(tap: .cghidEventTap)
        }
    }
}
