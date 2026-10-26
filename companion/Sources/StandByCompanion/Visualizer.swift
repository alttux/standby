import Accelerate
import AVFoundation
import CoreAudio

/// Turns a stream of mono samples into 24 log-spaced bands (0…1). Fed from one audio thread.
final class Spectrum {
    static let bandCount = 24
    private let n = 1024
    private let log2n: vDSP_Length
    private let setup: FFTSetup?
    private var window = [Float]()
    private var ring = [Float]()
    private var filled = 0
    private let lock = NSLock()
    private var bands = [Float](repeating: 0, count: Spectrum.bandCount)
    var rate: Float = 48000

    init() {
        log2n = vDSP_Length(log2(Float(n)))
        setup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2))
        window = [Float](repeating: 0, count: n)
        vDSP_hann_window(&window, vDSP_Length(n), Int32(vDSP_HANN_NORM))
        ring = [Float](repeating: 0, count: n)
    }

    deinit { if let setup { vDSP_destroy_fftsetup(setup) } }

    /// `samples` holds `frames` frames of `channels` interleaved Float32 samples.
    func feed(_ samples: UnsafePointer<Float>, frames: Int, channels: Int) {
        for f in 0..<frames {
            var v: Float = 0
            for c in 0..<channels { v += samples[f * channels + c] }
            ring[filled] = v / Float(channels)
            filled += 1
            if filled == n {
                analyse()
                filled = 0
            }
        }
    }

    func reset() {
        lock.lock(); bands = [Float](repeating: 0, count: Self.bandCount); lock.unlock()
    }

    func snapshot() -> [Float] {
        lock.lock(); defer { lock.unlock() }
        return bands
    }

    private func analyse() {
        guard let setup else { return }
        var x = [Float](repeating: 0, count: n)
        vDSP_vmul(ring, 1, window, 1, &x, 1, vDSP_Length(n))
        var re = [Float](repeating: 0, count: n / 2)
        var im = [Float](repeating: 0, count: n / 2)
        var mags = [Float](repeating: 0, count: n / 2)
        re.withUnsafeMutableBufferPointer { rp in
            im.withUnsafeMutableBufferPointer { ip in
                var split = DSPSplitComplex(realp: rp.baseAddress!, imagp: ip.baseAddress!)
                x.withUnsafeBufferPointer { xp in
                    xp.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: n / 2) {
                        vDSP_ctoz($0, 2, &split, 1, vDSP_Length(n / 2))
                    }
                }
                vDSP_fft_zrip(setup, &split, 1, log2n, FFTDirection(FFT_FORWARD))
                vDSP_zvabs(&split, 1, &mags, 1, vDSP_Length(n / 2))
            }
        }
        // log-spaced bands from 80 Hz to 8 kHz
        let binHz = rate / Float(n)
        let lo: Float = 80
        let hi: Float = min(8000, rate / 2)
        var out = [Float](repeating: 0, count: Self.bandCount)
        for b in 0..<Self.bandCount {
            let f0 = lo * pow(hi / lo, Float(b) / Float(Self.bandCount))
            let f1 = lo * pow(hi / lo, Float(b + 1) / Float(Self.bandCount))
            let i0 = max(1, Int(f0 / binHz))
            let i1 = max(i0 + 1, min(n / 2, Int(f1 / binHz)))
            var m: Float = 0
            for i in i0..<i1 { m = max(m, mags[i]) }
            let db = 20 * log10(max(m / Float(n), 1e-7))
            out[b] = max(0, min(1, (db + 70) / 55))
        }
        lock.lock()
        for i in 0..<Self.bandCount { bands[i] = max(out[i], bands[i] * 0.6) }
        lock.unlock()
    }
}

/// Microphone capture through AVAudioEngine.
final class MicSource {
    let spectrum = Spectrum()
    private var engine: AVAudioEngine?
    private var requesting = false
    private var observer: NSObjectProtocol?
    private var restarts: [Date] = []
    var running: Bool { engine != nil }

    func start() {
        guard engine == nil else { return }
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .notDetermined:
            guard !requesting else { return }
            requesting = true
            AVCaptureDevice.requestAccess(for: .audio) { ok in
                DispatchQueue.main.async {
                    self.requesting = false
                    if ok { self.start() } else { Log.w("Microphone access denied") }
                }
            }
            return
        case .denied, .restricted:
            Log.w("Microphone access denied: System Settings → Privacy & Security → Microphone")
            return
        default: break
        }
        let e = AVAudioEngine()
        let input = e.inputNode
        let fmt = input.outputFormat(forBus: 0)
        let hw = input.inputFormat(forBus: 0)
        guard fmt.sampleRate > 0, hw.sampleRate > 0, hw.channelCount > 0 else { return Log.w("No microphone available") }
        // While a Bluetooth headset switches profile the hardware rate differs from the node format and
        // installTap would throw an Objective-C exception (which crashes the app): wait for the next try.
        guard fmt.sampleRate == hw.sampleRate else { return }   // the hub calls start() again every second
        spectrum.rate = Float(fmt.sampleRate)
        input.installTap(onBus: 0, bufferSize: 1024, format: fmt) { [weak self] buf, _ in
            guard let ch = buf.floatChannelData?[0] else { return }
            self?.spectrum.feed(ch, frames: Int(buf.frameLength), channels: 1)
        }
        // the input is only pulled while the graph reaches an output; keep that output silent
        e.connect(input, to: e.mainMixerNode, format: fmt)
        e.mainMixerNode.outputVolume = 0
        do {
            try e.start()
            engine = e
            Log.i("Mic visualizer started (\(Audio.name(Audio.defaultDevice(input: true))), \(Int(fmt.sampleRate)) Hz)")
            // Bluetooth headsets switch profile when the mic opens, which stops the engine: rebuild it.
            observer = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: e, queue: .main) { [weak self] _ in
                guard let self, self.engine === e else { return }
                let now = Date()
                self.restarts = self.restarts.filter { now.timeIntervalSince($0) < 60 } + [now]
                guard self.restarts.count <= 6 else { return Log.w("Mic visualizer: audio device keeps changing, giving up") }
                self.stop(quiet: true)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.start() }
            }
        } catch {
            Log.w("Mic visualizer: \(error.localizedDescription)")
        }
    }

    func stop(quiet: Bool = false) {
        guard let e = engine else { return }
        if let o = observer { NotificationCenter.default.removeObserver(o) }
        observer = nil
        e.inputNode.removeTap(onBus: 0)
        e.stop()
        engine = nil
        spectrum.reset()
        if !quiet { Log.i("Mic visualizer stopped") }
    }
}

/// Everything the Mac plays (the other side of a call, music…) via a Core Audio process tap (macOS 14.2+).
/// Unlike the microphone this never switches Bluetooth headsets into the headset profile.
final class SystemAudioSource {
    let spectrum = Spectrum()
    private var tapID = AudioObjectID(kAudioObjectUnknown)
    private var aggID = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?
    private let queue = DispatchQueue(label: "system-audio-tap", qos: .userInteractive)
    private(set) var outputUID = ""
    var running: Bool { procID != nil }

    static var supported: Bool {
        if #available(macOS 14.2, *) { return true }
        return false
    }

    func start() {
        guard !running else { return }
        guard #available(macOS 14.2, *) else { return }
        let outDev = Audio.defaultDevice(input: false)
        guard let uid = Audio.uid(outDev) else { return Log.w("System audio: no output device") }

        let desc = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
        desc.uuid = UUID()
        desc.name = "StandBy visualizer"
        desc.isPrivate = true
        desc.muteBehavior = .unmuted
        var tap = AudioObjectID(kAudioObjectUnknown)
        var err = AudioHardwareCreateProcessTap(desc, &tap)
        guard err == noErr else { return Log.w("System audio tap failed (\(err))") }
        tapID = tap

        let agg: [String: Any] = [
            kAudioAggregateDeviceNameKey: "StandBy Visualizer",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceMainSubDeviceKey: uid,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: uid]],
            kAudioAggregateDeviceTapListKey: [[kAudioSubTapDriftCompensationKey: true, kAudioSubTapUIDKey: desc.uuid.uuidString]],
        ]
        var aggDev = AudioObjectID(kAudioObjectUnknown)
        err = AudioHardwareCreateAggregateDevice(agg as CFDictionary, &aggDev)
        guard err == noErr else {
            cleanup()
            return Log.w("System audio aggregate device failed (\(err))")
        }
        aggID = aggDev

        var fmt = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        AudioObjectGetPropertyData(tapID, &addr, 0, nil, &size, &fmt)
        spectrum.rate = fmt.mSampleRate > 0 ? Float(fmt.mSampleRate) : 48000
        let isFloat = fmt.mFormatFlags & kAudioFormatFlagIsFloat != 0 || fmt.mFormatID == 0

        let spectrum = self.spectrum
        var proc: AudioDeviceIOProcID?
        err = AudioDeviceCreateIOProcIDWithBlock(&proc, aggID, queue) { _, input, _, _, _ in
            guard isFloat else { return }
            let list = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input))
            guard let buf = list.first, let data = buf.mData else { return }
            let channels = max(1, Int(buf.mNumberChannels))
            let frames = Int(buf.mDataByteSize) / (MemoryLayout<Float>.size * channels)
            spectrum.feed(data.assumingMemoryBound(to: Float.self), frames: frames, channels: channels)
        }
        guard err == noErr, let proc else {
            cleanup()
            return Log.w("System audio IO proc failed (\(err))")
        }
        procID = proc
        err = AudioDeviceStart(aggID, proc)
        guard err == noErr else {
            cleanup()
            return Log.w("System audio start failed (\(err)); allow \"System Audio Recording\" in Privacy & Security")
        }
        outputUID = uid
        Log.i("System audio visualizer started (\(Audio.name(outDev)), \(Int(spectrum.rate)) Hz)")
    }

    func stop(quiet: Bool = false) {
        guard running else { return }
        cleanup()
        spectrum.reset()
        if !quiet { Log.i("System audio visualizer stopped") }
    }

    private func cleanup() {
        if aggID != kAudioObjectUnknown {
            if let p = procID {
                AudioDeviceStop(aggID, p)
                AudioDeviceDestroyIOProcID(aggID, p)
            }
            AudioHardwareDestroyAggregateDevice(aggID)
        }
        if #available(macOS 14.2, *), tapID != kAudioObjectUnknown { AudioHardwareDestroyProcessTap(tapID) }
        procID = nil
        aggID = AudioObjectID(kAudioObjectUnknown)
        tapID = AudioObjectID(kAudioObjectUnknown)
        outputUID = ""
    }
}

/// Microphone visualizer (the system-audio source is kept but unused).
final class Visualizer {
    static let bandCount = Spectrum.bandCount
    let mic = MicSource()
    let system = SystemAudioSource()

    var running: Bool { mic.running || system.running }

    /// The call card visualizes the microphone only.
    func update(on: Bool, withMic: Bool) {
        system.stop()
        if on { mic.start() } else { mic.stop() }
    }

    func snapshot() -> [Float] {
        let a = mic.running ? mic.spectrum.snapshot() : nil
        let b = system.running ? system.spectrum.snapshot() : nil
        switch (a, b) {
        case let (a?, b?): return zip(a, b).map { max($0, $1) }
        case let (a?, nil): return a
        case let (nil, b?): return b
        default: return [Float](repeating: 0, count: Self.bandCount)
        }
    }
}
