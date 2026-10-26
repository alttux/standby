import Darwin
import Foundation
import IOKit
import IOKit.ps

/// CPU, GPU, memory, network, disk and battery figures of this Mac.
final class SystemStats {
    private var prevCPU: (busy: UInt64, idle: UInt64)?
    private var prevNet: [String: (rx: UInt32, tx: UInt32)] = [:]
    private var prevNetTime: Date?
    private let cores = ProcessInfo.processInfo.activeProcessorCount
    private lazy var gpuName: String = detectGPUName()

    func sample() -> [String: Any] {
        var o: [String: Any] = ["type": "stats"]
        o["cpu"] = cpu()
        o["cores"] = cores
        var load = [Double](repeating: 0, count: 3)
        if getloadavg(&load, 3) == 3 { o["load"] = load }
        o["gpu"] = gpu()
        o["gpuName"] = gpuName
        let m = memory()
        o["memUsed"] = Double(m.used)
        o["memTotal"] = Double(m.total)
        o["swapUsed"] = Double(swapUsed())
        let n = network()
        o["netRx"] = n.rx
        o["netTx"] = n.tx
        if let a = try? FileManager.default.attributesOfFileSystem(forPath: "/") {
            o["diskFree"] = (a[.systemFreeSize] as? NSNumber)?.doubleValue ?? 0
            o["diskTotal"] = (a[.systemSize] as? NSNumber)?.doubleValue ?? 0
        }
        if let b = battery() {
            o["battery"] = b.percent
            o["charging"] = b.charging
        }
        o["uptime"] = ProcessInfo.processInfo.systemUptime
        o["thermal"] = ["nominal", "fair", "serious", "critical"][ProcessInfo.processInfo.thermalState.rawValue]
        return o
    }

    private func cpu() -> Double {
        var numCPU: natural_t = 0
        var info: processor_info_array_t?
        var count: mach_msg_type_number_t = 0
        guard host_processor_info(mach_host_self(), PROCESSOR_CPU_LOAD_INFO, &numCPU, &info, &count) == KERN_SUCCESS,
              let info else { return 0 }
        var busy: UInt64 = 0
        var idle: UInt64 = 0
        for i in 0..<Int(numCPU) {
            let base = Int(CPU_STATE_MAX) * i
            func t(_ s: Int32) -> UInt64 { UInt64(UInt32(bitPattern: info[base + Int(s)])) }
            busy += t(CPU_STATE_USER) + t(CPU_STATE_SYSTEM) + t(CPU_STATE_NICE)
            idle += t(CPU_STATE_IDLE)
        }
        vm_deallocate(mach_task_self_, vm_address_t(bitPattern: info), vm_size_t(Int(count) * MemoryLayout<integer_t>.stride))
        defer { prevCPU = (busy, idle) }
        guard let p = prevCPU, busy >= p.busy, idle >= p.idle else { return 0 }
        let b = Double(busy - p.busy)
        let i = Double(idle - p.idle)
        return b + i > 0 ? b / (b + i) * 100 : 0
    }

    private func gpu() -> Double {
        var it: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("IOAccelerator"), &it) == KERN_SUCCESS else { return 0 }
        defer { IOObjectRelease(it) }
        var best = 0.0
        while true {
            let s = IOIteratorNext(it)
            if s == 0 { break }
            defer { IOObjectRelease(s) }
            guard let props = IORegistryEntryCreateCFProperty(s, "PerformanceStatistics" as CFString, kCFAllocatorDefault, 0)?
                .takeRetainedValue() as? [String: Any] else { continue }
            for key in ["Device Utilization %", "GPU Activity(%)", "Renderer Utilization %"] {
                if let v = props[key] as? NSNumber {
                    best = max(best, v.doubleValue)
                    break
                }
            }
        }
        return min(100, best)
    }

    private func detectGPUName() -> String {
        var it: io_iterator_t = 0
        if IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("IOAccelerator"), &it) == KERN_SUCCESS {
            defer { IOObjectRelease(it) }
            let s = IOIteratorNext(it)
            if s != 0 {
                defer { IOObjectRelease(s) }
                if let v = IORegistryEntryCreateCFProperty(s, "model" as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() {
                    if let str = v as? String { return str }
                    if let d = v as? Data, let str = String(data: d, encoding: .utf8) { return str.trimmingCharacters(in: CharacterSet(charactersIn: "\0")) }
                }
            }
        }
        var size = 0
        sysctlbyname("machdep.cpu.brand_string", nil, &size, nil, 0)
        var buf = [CChar](repeating: 0, count: max(size, 1))
        sysctlbyname("machdep.cpu.brand_string", &buf, &size, nil, 0)
        return String(cString: buf)
    }

    private func memory() -> (used: UInt64, total: UInt64) {
        var stats = vm_statistics64()
        var count = mach_msg_type_number_t(MemoryLayout<vm_statistics64_data_t>.size / MemoryLayout<integer_t>.size)
        let r = withUnsafeMutablePointer(to: &stats) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { host_statistics64(mach_host_self(), HOST_VM_INFO64, $0, &count) }
        }
        let total = ProcessInfo.processInfo.physicalMemory
        guard r == KERN_SUCCESS else { return (0, total) }
        var pageSize: vm_size_t = 0
        host_page_size(mach_host_self(), &pageSize)
        let p = UInt64(pageSize)
        let anon = UInt64(stats.internal_page_count)
        let purgeable = UInt64(stats.purgeable_count)
        let app = anon > purgeable ? anon - purgeable : 0
        let used = (app + UInt64(stats.wire_count) + UInt64(stats.compressor_page_count)) * p
        return (min(used, total), total)
    }

    private func swapUsed() -> UInt64 {
        var xsw = xsw_usage()
        var size = MemoryLayout<xsw_usage>.size
        guard sysctlbyname("vm.swapusage", &xsw, &size, nil, 0) == 0 else { return 0 }
        return xsw.xsu_used
    }

    /// Bytes per second received / sent over physical interfaces.
    private func network() -> (rx: Double, tx: Double) {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return (0, 0) }
        defer { freeifaddrs(ifaddr) }
        var cur: [String: (rx: UInt32, tx: UInt32)] = [:]
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = p.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_LINK), let data = ifa.ifa_data else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("pdp_ip") else { continue }
            let d = data.assumingMemoryBound(to: if_data.self).pointee
            cur[name] = (d.ifi_ibytes, d.ifi_obytes)
        }
        let now = Date()
        defer {
            prevNet = cur
            prevNetTime = now
        }
        guard let t0 = prevNetTime else { return (0, 0) }
        let dt = max(0.2, now.timeIntervalSince(t0))
        var rx: UInt64 = 0
        var tx: UInt64 = 0
        for (k, v) in cur {
            guard let p = prevNet[k] else { continue }
            rx += UInt64(v.rx &- p.rx)   // 32-bit counters wrap around
            tx += UInt64(v.tx &- p.tx)
        }
        return (Double(rx) / dt, Double(tx) / dt)
    }

    private func battery() -> (percent: Int, charging: Bool)? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
        for ps in list {
            guard let d = IOPSGetPowerSourceDescription(info, ps)?.takeUnretainedValue() as? [String: Any],
                  (d[kIOPSTypeKey] as? String) == kIOPSInternalBatteryType,
                  let cap = d[kIOPSCurrentCapacityKey] as? Int else { continue }
            let max = d[kIOPSMaxCapacityKey] as? Int ?? 100
            return (cap * 100 / Swift.max(1, max), d[kIOPSIsChargingKey] as? Bool ?? false)
        }
        return nil
    }
}
