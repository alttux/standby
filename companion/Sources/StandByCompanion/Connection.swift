import Foundation
import Network

/// Keeps a JSON-lines TCP session with the launcher through `adb forward`.
final class Connection: ObservableObject {
    enum Status: Equatable {
        case starting
        case noAdb
        case noDevice
        case waitingApp(String)   // device found, launcher not answering
        case connected(String)    // device description

        var text: String {
            switch self {
            case .starting: return "Starting…"
            case .noAdb: return "adb not found"
            case .noDevice: return "No Android device (USB debugging?)"
            case .waitingApp(let d): return "\(d): launcher not responding"
            case .connected(let d): return "Connected to \(d)"
            }
        }

        var isConnected: Bool { if case .connected = self { return true } else { return false } }
    }

    @Published private(set) var status: Status = .starting
    /// Average round-trip time of the last pings, ms.
    @Published private(set) var latency: Double?
    /// "USB" or "Wi-Fi" while connected.
    @Published private(set) var transport = ""

    var onMessage: (([String: Any]) -> Void)?
    var onConnected: (() -> Void)?

    private let q = DispatchQueue(label: "connection")
    private var conn: NWConnection?
    private var buffer = Data()
    private var attempting = false
    private var helloSeen = false
    private var lastRx = Date()
    private var timer: Timer?
    private var device: ADB.Device?
    private var pingSent: [Int: Date] = [:]
    private var pingId = 0
    private var pingWaiters: [Int: (Double?) -> Void] = [:]
    private var rtts: [Double] = []
    private var lostPings = 0
    private var ticks = 0
    private var lastWifiRepair = Date.distantPast
    private var repairInterval: TimeInterval = 60
    private var probing = false
    private let probeQ = DispatchQueue(label: "wifi-probe")

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.tick() }
        tick()
    }

    /// Drops the session and reconnects (e.g. after the port changed).
    func restart() {
        q.async {
            self.drop(reason: "restart")
            self.attempt()
        }
    }

    private func set(_ s: Status) {
        DispatchQueue.main.async {
            if self.status != s {
                self.status = s
                Log.i(s.text)
            }
        }
    }

    private func tick() {
        q.async {
            self.ticks += 1
            if self.helloSeen {
                if Date().timeIntervalSince(self.lastRx) > 9 {
                    self.drop(reason: "timeout")
                } else {
                    self.expirePings()
                    self.ping(nil)
                    if self.ticks % 20 == 0 { self.logLinkQuality() }
                    // running over USB while Wi-Fi is preferred: move over once it is reachable again
                    let st = Settings.shared
                    if st.wifiEnabled, let d = self.device, !d.isNetwork, !st.wifiAddress.isEmpty, self.ticks % 3 == 0, !self.probing {
                        self.probing = true
                        let addr = st.wifiAddress
                        // adb connect may block for seconds; keep it off the data queue
                        self.probeQ.async {
                            let ok = ADB.devices().contains(where: { $0.serial == addr && $0.state == "device" }) || ADB.connect(addr)
                            self.q.async {
                                self.probing = false
                                guard ok, self.helloSeen, self.device?.isNetwork == false else { return }
                                Log.i("Wi-Fi is back, switching from USB")
                                self.drop(reason: "switching to Wi-Fi")
                                self.attempt()
                            }
                        }
                    }
                }
                return
            }
            if !self.attempting { self.attempt() }
        }
    }

    /// Pings older than 5 s without a pong count as lost.
    private func expirePings() {
        let now = Date()
        for (id, t) in pingSent where now.timeIntervalSince(t) > 5 {
            pingSent.removeValue(forKey: id)
            lostPings += 1
        }
    }

    private func logLinkQuality() {
        guard !rtts.isEmpty else { return }
        let avg = rtts.reduce(0, +) / Double(rtts.count)
        Log.i(String(format: "Link %@: ping avg %.0f ms, max %.0f ms, lost %d", transport, avg, rtts.max() ?? 0, lostPings))
    }

    /// Makes sure `adb connect` is up when Wi-Fi is preferred; repairs it over USB (new IP, phone rebooted).
    private func ensureWifi() {
        let st = Settings.shared
        guard st.wifiEnabled, !st.wifiAddress.isEmpty else { return }
        let list = ADB.devices()
        if list.contains(where: { $0.serial == st.wifiAddress && $0.state == "device" }) { return }
        if list.contains(where: { $0.serial == st.wifiAddress }) { ADB.run(["disconnect", st.wifiAddress], timeout: 4) }
        if ADB.connect(st.wifiAddress) { return Log.i("adb connected over Wi-Fi: \(st.wifiAddress)") }
        guard let usb = list.first(where: { !$0.isNetwork && $0.state == "device" }),
              Date().timeIntervalSince(lastWifiRepair) > repairInterval else { return }
        lastWifiRepair = Date()
        // `adb tcpip` restarts adbd and drops the USB link too, so only run it when TCP mode is really off
        let tcpPort = ADB.run(["-s", usb.serial, "shell", "getprop", "service.adb.tcp.port"], timeout: 5).out
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var restored: String?
        if tcpPort == String(ADB.tcpPort), let ip = ADB.wifiIP(usb.serial) {
            let addr = "\(ip):\(ADB.tcpPort)"
            if ADB.connect(addr) { restored = addr } else { Log.w("Phone listens on \(addr) but the Mac cannot reach it (same network?)") }
        } else {
            Log.i("Wi-Fi adb is off on the phone, re-enabling it over USB")
            if case .success(let addr) = ADB.enableWifi(usbSerial: usb.serial) { restored = addr }
        }
        if let addr = restored {
            repairInterval = 60
            DispatchQueue.main.async { st.wifiAddress = addr }
            Log.i("Wi-Fi adb restored: \(addr)")
        } else {
            repairInterval = min(repairInterval * 2, 600)
        }
    }

    private func attempt() {
        attempting = true
        ensureWifi()
        guard ADB.path != nil else {
            set(.noAdb)
            attempting = false
            return
        }
        guard let dev = ADB.pick() else {
            set(.noDevice)
            attempting = false
            return
        }
        device = dev
        let port = Settings.shared.port
        let fwd = ADB.run(["-s", dev.serial, "forward", "tcp:\(port)", "tcp:\(port)"], timeout: 5)
        if fwd.status != 0 {
            Log.w("adb forward failed: \(fwd.out.trimmingCharacters(in: .whitespacesAndNewlines))")
            set(.waitingApp(describe(dev)))
            attempting = false
            return
        }
        let c = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: UInt16(port))!, using: .tcp)
        conn = c
        buffer = Data()
        helloSeen = false
        c.stateUpdateHandler = { [weak self] st in
            guard let self else { return }
            switch st {
            case .ready:
                self.sendNow(["type": "hello", "name": Host.current().localizedName ?? "Mac", "version": 1])
                self.receive(c)
                // the launcher answers with its own hello; adb accepts even if nothing listens
                self.q.asyncAfter(deadline: .now() + 4) {
                    if self.conn === c && !self.helloSeen {
                        self.set(.waitingApp(self.describe(dev)))
                        self.drop(reason: nil)
                    }
                }
            case .failed, .cancelled:
                if self.conn === c { self.drop(reason: nil) }
            default: break
            }
        }
        c.start(queue: q)
    }

    private func describe(_ d: ADB.Device) -> String { d.model.isEmpty ? d.serial : d.model }

    private func drop(reason: String?) {
        let wasConnected = helloSeen
        conn?.stateUpdateHandler = nil
        conn?.cancel()
        conn = nil
        helloSeen = false
        attempting = false
        pingSent.removeAll()
        DispatchQueue.main.async { self.transport = ""; self.latency = nil }
        if wasConnected {
            Log.i("Disconnected\(reason.map { " (\($0))" } ?? "")")
            if let d = device { set(.waitingApp(describe(d))) }
        }
    }

    private func receive(_ c: NWConnection) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self] data, _, complete, error in
            guard let self, self.conn === c else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                self.lastRx = Date()
                while let nl = self.buffer.firstIndex(of: 0x0A) {
                    let line = self.buffer.subdata(in: self.buffer.startIndex..<nl)
                    self.buffer.removeSubrange(self.buffer.startIndex...nl)
                    if let obj = try? JSONSerialization.jsonObject(with: line) as? [String: Any] { self.handle(obj) }
                }
            }
            if complete || error != nil {
                self.drop(reason: error?.localizedDescription ?? "closed by device")
                return
            }
            self.receive(c)
        }
    }

    private func handle(_ o: [String: Any]) {
        switch o["type"] as? String {
        case "hello":
            helloSeen = true
            attempting = false
            let name = (o["device"] as? String) ?? device.map(describe) ?? "Android"
            let tr = device?.transport ?? ""
            rtts.removeAll()
            lostPings = 0
            DispatchQueue.main.async { self.transport = tr; self.latency = nil }
            set(.connected("\(name) · \(tr)"))
            Log.i("Launcher hello: \(name), Android \(o["android"] as? String ?? "?")")
            DispatchQueue.main.async { self.onConnected?() }
        case "pong":
            if let id = o["id"] as? Int, let t = pingSent.removeValue(forKey: id) {
                let ms = Date().timeIntervalSince(t) * 1000
                rtts.append(ms)
                if rtts.count > 40 { rtts.removeFirst(rtts.count - 40) }
                let avg = rtts.reduce(0, +) / Double(rtts.count)
                DispatchQueue.main.async { self.latency = avg }
                if let w = pingWaiters.removeValue(forKey: id) { DispatchQueue.main.async { w(ms) } }
            }
        default:
            DispatchQueue.main.async { self.onMessage?(o) }
        }
    }

    /// Sends a ping; the callback gets the round-trip time in ms or nil on timeout.
    func ping(_ done: ((Double?) -> Void)?) {
        q.async {
            guard self.helloSeen else {
                if let done { DispatchQueue.main.async { done(nil) } }
                return
            }
            self.pingId += 1
            let id = self.pingId
            self.pingSent[id] = Date()
            if let done {
                self.pingWaiters[id] = done
                self.q.asyncAfter(deadline: .now() + 3) {
                    if let w = self.pingWaiters.removeValue(forKey: id) { DispatchQueue.main.async { w(nil) } }
                }
            }
            self.sendNow(["type": "ping", "id": id])
        }
    }

    func send(_ obj: [String: Any]) {
        q.async { if self.helloSeen { self.sendNow(obj) } }
    }

    private func sendNow(_ obj: [String: Any]) {
        guard let c = conn, var data = try? JSONSerialization.data(withJSONObject: obj) else { return }
        data.append(0x0A)
        c.send(content: data, completion: .contentProcessed { err in
            if let err { Log.w("send: \(err.localizedDescription)") }
        })
    }
}
