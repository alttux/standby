import Foundation
import ServiceManagement

/// In-memory log shown in the Logs window (and mirrored to stdout).
final class Log: ObservableObject {
    static let shared = Log()

    struct Entry: Identifiable {
        let id: Int
        let time: Date
        let level: String
        let text: String
    }

    @Published private(set) var entries: [Entry] = []
    private var counter = 0
    private let fmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    static func i(_ s: String) { shared.add("I", s) }
    static func w(_ s: String) { shared.add("W", s) }

    static let fileURL = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/StandByCompanion.log")
    private let fileQ = DispatchQueue(label: "log-file")
    private lazy var file: FileHandle? = {
        let fm = FileManager.default
        let url = Log.fileURL
        // keep the file small: start over when it grows past 1 MB
        if let size = (try? fm.attributesOfItem(atPath: url.path))?[.size] as? Int, size > 1_000_000 { try? fm.removeItem(at: url) }
        if !fm.fileExists(atPath: url.path) { fm.createFile(atPath: url.path, contents: nil) }
        let h = try? FileHandle(forWritingTo: url)
        h?.seekToEndOfFile()
        return h
    }()

    private func add(_ level: String, _ text: String) {
        let now = Date()
        let line = "\(fmt.string(from: now)) \(level) \(text)"
        print(line)
        fileQ.async { self.file?.write((line + "\n").data(using: .utf8)!) }
        DispatchQueue.main.async {
            self.counter += 1
            self.entries.append(Entry(id: self.counter, time: now, level: level, text: text))
            if self.entries.count > 1500 { self.entries.removeFirst(self.entries.count - 1500) }
        }
    }

    func clear() { entries.removeAll() }

    func text() -> String {
        entries.map { "\(fmt.string(from: $0.time)) \($0.level) \($0.text)" }.joined(separator: "\n")
    }

    func format(_ e: Entry) -> String { "\(fmt.string(from: e.time))  \(e.text)" }
}

/// User settings persisted in UserDefaults.
final class Settings: ObservableObject {
    static let shared = Settings()
    private let d = UserDefaults.standard

    @Published var adbPath: String { didSet { d.set(adbPath, forKey: "adbPath") } }
    @Published var serial: String { didSet { d.set(serial, forKey: "serial") } }
    @Published var port: Int { didSet { d.set(port, forKey: "port") } }
    @Published var sendStats: Bool { didSet { d.set(sendStats, forKey: "sendStats") } }
    @Published var sendMedia: Bool { didSet { d.set(sendMedia, forKey: "sendMedia") } }
    @Published var sendCall: Bool { didSet { d.set(sendCall, forKey: "sendCall") } }
    @Published var visualizer: Bool { didSet { d.set(visualizer, forKey: "visualizer") } }
    /// Prefer the Wi-Fi transport (`adb connect wifiAddress`) over USB.
    @Published var wifiEnabled: Bool { didSet { d.set(wifiEnabled, forKey: "wifiEnabled") } }
    /// "ip:port" of the phone's adb over TCP.
    @Published var wifiAddress: String { didSet { d.set(wifiAddress, forKey: "wifiAddress") } }

    private init() {
        d.register(defaults: ["port": 47800, "sendStats": true, "sendMedia": true, "sendCall": true, "visualizer": true])
        adbPath = d.string(forKey: "adbPath") ?? ""
        serial = d.string(forKey: "serial") ?? ""
        port = d.integer(forKey: "port")
        sendStats = d.bool(forKey: "sendStats")
        sendMedia = d.bool(forKey: "sendMedia")
        sendCall = d.bool(forKey: "sendCall")
        visualizer = d.bool(forKey: "visualizer")
        wifiEnabled = d.bool(forKey: "wifiEnabled")
        wifiAddress = d.string(forKey: "wifiAddress") ?? ""
    }

    var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                Log.w("Launch at login: \(error.localizedDescription)")
            }
            objectWillChange.send()
        }
    }
}

struct ADBError: Error, CustomStringConvertible {
    let description: String
    init(_ d: String) { description = d }
}

/// Thin wrapper around the adb command line tool.
enum ADB {
    struct Device: Hashable {
        let serial: String
        let state: String
        let model: String
    }

    static let candidates = [
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
        NSHomeDirectory() + "/Library/Android/sdk/platform-tools/adb",
        "/Applications/Android Studio.app/Contents/platform-tools/adb",
    ]

    static func detect() -> String? {
        candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    static var path: String? {
        let p = Settings.shared.adbPath
        if !p.isEmpty, FileManager.default.isExecutableFile(atPath: p) { return p }
        return detect()
    }

    /// Runs adb synchronously (call off the main thread).
    @discardableResult
    static func run(_ args: [String], timeout: TimeInterval = 10) -> (status: Int32, out: String) {
        guard let adb = path else { return (-1, "adb not found") }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: adb)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return (-1, error.localizedDescription) }
        let deadline = Date().addingTimeInterval(timeout)
        var data = Data()
        let reader = DispatchQueue(label: "adb-read")
        let done = DispatchSemaphore(value: 0)
        reader.async {
            data = pipe.fileHandleForReading.readDataToEndOfFile()
            done.signal()
        }
        while p.isRunning && Date() < deadline { usleep(20_000) }
        var timedOut = false
        if p.isRunning {
            timedOut = true
            p.terminate()
            let killAt = Date().addingTimeInterval(1)
            while p.isRunning && Date() < killAt { usleep(20_000) }
            if p.isRunning { kill(p.processIdentifier, SIGKILL) }
            p.waitUntilExit()
        }
        _ = done.wait(timeout: .now() + 1)
        let out = String(data: data, encoding: .utf8) ?? ""
        // terminationStatus throws while the task is still running; it has exited here
        return (timedOut ? -1 : p.terminationStatus, timedOut ? "timeout: adb \(args.joined(separator: " "))" : out)
    }

    static func devices() -> [Device] {
        let (st, out) = run(["devices", "-l"], timeout: 5)
        guard st == 0 else { return [] }
        return out.split(separator: "\n").dropFirst().compactMap { line in
            let parts = line.split(separator: " ", omittingEmptySubsequences: true)
            guard parts.count >= 2 else { return nil }
            let model = parts.first { $0.hasPrefix("model:") }.map { String($0.dropFirst(6)).replacingOccurrences(of: "_", with: " ") } ?? ""
            return Device(serial: String(parts[0]), state: String(parts[1]), model: model)
        }
    }

    /// The device to talk to: the configured serial or the first one online.
    static func pick() -> Device? {
        let list = devices().filter { $0.state == "device" }
        let st = Settings.shared
        if st.wifiEnabled, !st.wifiAddress.isEmpty, let w = list.first(where: { $0.serial == st.wifiAddress }) { return w }
        if !st.serial.isEmpty { return list.first { $0.serial == st.serial } }
        // USB first: a stale network entry should not win over a cable
        return list.first { !$0.isNetwork } ?? list.first
    }

    // MARK: Wi-Fi debugging

    static let tcpPort = 5555

    static func wifiIP(_ serial: String) -> String? {
        let out = run(["-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0"], timeout: 5).out
        guard let r = out.range(of: #"inet (\d+\.\d+\.\d+\.\d+)"#, options: .regularExpression) else { return nil }
        return String(out[r].dropFirst(5))
    }

    private static var lastServerRestart = Date.distantPast

    @discardableResult
    static func connect(_ address: String) -> Bool {
        let out = run(["connect", address], timeout: 6).out
        if out.contains("connected to") { return true }
        // macOS Local Network privacy can block an adb server started by another app (Terminal, an IDE…):
        // adb then reports "No route to host" although the phone answers us. Restart the server from here.
        if out.contains("No route to host"), Date().timeIntervalSince(lastServerRestart) > 600, tcpReachable(address) {
            lastServerRestart = Date()
            Log.w("adb server cannot reach the local network (Local Network permission); restarting it from the companion")
            run(["kill-server"], timeout: 5)
            run(["start-server"], timeout: 10)
            return run(["connect", address], timeout: 6).out.contains("connected to")
        }
        return false
    }

    /// Plain TCP connect from this process, 2 s timeout.
    static func tcpReachable(_ address: String) -> Bool {
        let parts = address.split(separator: ":")
        guard parts.count == 2, let port = UInt16(parts[1]) else { return false }
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
        var sa = sockaddr_in()
        sa.sin_family = sa_family_t(AF_INET)
        sa.sin_port = port.bigEndian
        guard inet_pton(AF_INET, String(parts[0]), &sa.sin_addr) == 1 else { return false }
        let r = withUnsafePointer(to: &sa) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        if r == 0 { return true }
        guard errno == EINPROGRESS else { return false }
        var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
        guard poll(&pfd, 1, 2000) == 1 else { return false }
        var err: Int32 = 0
        var len = socklen_t(MemoryLayout<Int32>.size)
        getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len)
        return err == 0
    }

    /// Switches a USB-attached phone to adb over TCP and connects to it. Returns "ip:port" or an error text.
    static func enableWifi(usbSerial: String) -> Result<String, ADBError> {
        guard let ip = wifiIP(usbSerial) else { return .failure(ADBError("The phone is not connected to Wi-Fi.")) }
        let addr = "\(ip):\(tcpPort)"
        let r = run(["-s", usbSerial, "tcpip", String(tcpPort)], timeout: 8)
        guard r.status == 0 else { return .failure(ADBError("adb tcpip failed: \(r.out)")) }
        for _ in 0..<6 {
            sleep(1)
            if connect(addr) { return .success(addr) }
        }
        return .failure(ADBError("Could not reach \(addr). Are the Mac and the phone on the same network? macOS may also ask to allow Local Network access."))
    }

    /// Switches the phone back to USB-only adb.
    static func disableWifi(_ address: String) {
        if devices().contains(where: { $0.serial == address && $0.state == "device" }) {
            run(["-s", address, "usb"], timeout: 5)
        }
        run(["disconnect", address], timeout: 5)
    }
}

extension ADB.Device {
    var isNetwork: Bool { serial.contains(":") }
    var transport: String { isNetwork ? "Wi-Fi" : "USB" }
}
