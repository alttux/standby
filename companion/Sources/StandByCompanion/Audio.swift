import AppKit
import AudioToolbox
import CoreAudio

/// CoreAudio helpers for the default input/output devices.
enum Audio {
    private static func addr(_ sel: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal,
                             _ el: AudioObjectPropertyElement = kAudioObjectPropertyElementMain) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: sel, mScope: scope, mElement: el)
    }

    private static func scope(_ input: Bool) -> AudioObjectPropertyScope {
        input ? kAudioDevicePropertyScopeInput : kAudioDevicePropertyScopeOutput
    }

    static func defaultDevice(input: Bool) -> AudioDeviceID {
        var a = addr(input ? kAudioHardwarePropertyDefaultInputDevice : kAudioHardwarePropertyDefaultOutputDevice)
        var id = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size, &id)
        return id
    }

    static func uid(_ id: AudioDeviceID) -> String? {
        var a = addr(kAudioDevicePropertyDeviceUID)
        var uid: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &a, 0, nil, &size, &uid) == noErr, let u = uid else { return nil }
        return u.takeRetainedValue() as String
    }

    static func name(_ id: AudioDeviceID) -> String {
        var a = addr(kAudioObjectPropertyName)
        var name: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &a, 0, nil, &size, &name) == noErr, let n = name else { return "" }
        return n.takeRetainedValue() as String
    }

    private static func getU32(_ id: AudioObjectID, _ a: AudioObjectPropertyAddress) -> UInt32? {
        var a = a
        guard AudioObjectHasProperty(id, &a) else { return nil }
        var v: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        return AudioObjectGetPropertyData(id, &a, 0, nil, &size, &v) == noErr ? v : nil
    }

    private static func settable(_ id: AudioObjectID, _ a: AudioObjectPropertyAddress) -> Bool {
        var a = a
        guard AudioObjectHasProperty(id, &a) else { return false }
        var s: DarwinBoolean = false
        return AudioObjectIsPropertySettable(id, &a, &s) == noErr && s.boolValue
    }

    static func mute(input: Bool) -> Bool? {
        let id = defaultDevice(input: input)
        if let m = getU32(id, addr(kAudioDevicePropertyMute, scope(input))) { return m != 0 }
        if let v = volume(input: input) { return v < 0.001 }
        return nil
    }

    private static var savedInputVolume: Float = 0.75

    /// Mutes or unmutes; falls back to volume 0 when the device has no mute control.
    @discardableResult
    static func setMute(input: Bool, _ on: Bool) -> Bool {
        let id = defaultDevice(input: input)
        let a = addr(kAudioDevicePropertyMute, scope(input))
        if settable(id, a) {
            var v: UInt32 = on ? 1 : 0
            var aa = a
            if AudioObjectSetPropertyData(id, &aa, 0, nil, UInt32(MemoryLayout<UInt32>.size), &v) == noErr { return true }
        }
        if input {
            if on {
                if let v = volume(input: true), v > 0.001 { savedInputVolume = v }
                return setVolume(input: true, 0)
            }
            return setVolume(input: true, savedInputVolume)
        }
        return appleScript("set volume output muted \(on)")
    }

    static func volume(input: Bool) -> Float? {
        let id = defaultDevice(input: input)
        let candidates: [AudioObjectPropertyAddress] = [
            addr(kAudioHardwareServiceDeviceProperty_VirtualMainVolume, scope(input)),
            addr(kAudioDevicePropertyVolumeScalar, scope(input)),
            addr(kAudioDevicePropertyVolumeScalar, scope(input), 1),
        ]
        for c in candidates {
            var a = c
            guard AudioObjectHasProperty(id, &a) else { continue }
            var v: Float32 = 0
            var size = UInt32(MemoryLayout<Float32>.size)
            if AudioObjectGetPropertyData(id, &a, 0, nil, &size, &v) == noErr { return v }
        }
        return nil
    }

    @discardableResult
    static func setVolume(input: Bool, _ value: Float) -> Bool {
        let id = defaultDevice(input: input)
        var v = Float32(max(0, min(1, value)))
        var ok = false
        let candidates: [[AudioObjectPropertyAddress]] = [
            [addr(kAudioHardwareServiceDeviceProperty_VirtualMainVolume, scope(input))],
            [addr(kAudioDevicePropertyVolumeScalar, scope(input))],
            [addr(kAudioDevicePropertyVolumeScalar, scope(input), 1), addr(kAudioDevicePropertyVolumeScalar, scope(input), 2)],
        ]
        for group in candidates {
            for c in group where settable(id, c) {
                var a = c
                if AudioObjectSetPropertyData(id, &a, 0, nil, UInt32(MemoryLayout<Float32>.size), &v) == noErr { ok = true }
            }
            if ok { return true }
        }
        let pct = Int(v * 100)
        return appleScript(input ? "set volume input volume \(pct)" : "set volume output volume \(pct)")
    }

    static func isRunningSomewhere(input: Bool) -> Bool {
        (getU32(defaultDevice(input: input), addr(kAudioDevicePropertyDeviceIsRunningSomewhere)) ?? 0) != 0
    }

    /// Processes (other than us) currently capturing audio; macOS 14.2+.
    static func inputProcesses() -> [(pid: pid_t, bundle: String)]? {
        guard #available(macOS 14.2, *) else { return nil }
        var a = addr(kAudioHardwarePropertyProcessObjectList)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size) == noErr else { return nil }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size, &ids) == noErr else { return nil }
        let me = ProcessInfo.processInfo.processIdentifier
        var out: [(pid_t, String)] = []
        for id in ids {
            guard getU32(id, addr(kAudioProcessPropertyIsRunningInput)) == 1 else { continue }
            var pa = addr(kAudioProcessPropertyPID)
            var pid: pid_t = 0
            var ps = UInt32(MemoryLayout<pid_t>.size)
            AudioObjectGetPropertyData(id, &pa, 0, nil, &ps, &pid)
            if pid == me { continue }
            var ba = addr(kAudioProcessPropertyBundleID)
            var bid: Unmanaged<CFString>?
            var bs = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
            var bundle = ""
            if AudioObjectGetPropertyData(id, &ba, 0, nil, &bs, &bid) == noErr, let b = bid { bundle = b.takeRetainedValue() as String }
            out.append((pid, bundle))
        }
        return out
    }

    @discardableResult
    static func appleScript(_ src: String) -> Bool {
        var err: NSDictionary?
        NSAppleScript(source: src)?.executeAndReturnError(&err)
        if let err { Log.w("AppleScript: \(err[NSAppleScript.errorMessage] ?? err)") }
        return err == nil
    }
}
