import AppKit
import ApplicationServices
import CoreImage

/// The macOS Touch Bar, emulated the way sindresorhus/touch-bar-simulator does it (TouchBarView.swift):
/// `DFRSetStatus(2)` turns on a virtual Touch Bar, `SLSDFRDisplayStreamCreate` streams the frames the
/// system renders for it (the frontmost app's items + the Control Strip) and
/// `DFRFoundationPostEventWithMouseActivity` injects touches. Here the frames go to the phone instead of a window.
///
/// Differences to the original, needed on current macOS:
/// - the private symbols are resolved with dlsym (the SDK has no stubs for private frameworks);
/// - `CGDisplayStreamStart/Stop` are unavailable in the macOS 15 SDK, so they are resolved at runtime too.
final class TouchBarEmulator {
    private typealias GetStatus = @convention(c) () -> Int32
    private typealias SetStatus = @convention(c) (Int32) -> Void
    private typealias GetSize = @convention(c) () -> CGSize
    private typealias PostEvent = @convention(c) (UInt, CGPoint) -> Void
    private typealias StreamCreate = @convention(c) (Int32, DispatchQueue, @escaping CGDisplayStreamFrameAvailableHandler) -> Unmanaged<CGDisplayStream>?
    private typealias StreamCtl = @convention(c) (CGDisplayStream) -> Int32

    private struct API {
        let getStatus: GetStatus
        let setStatus: SetStatus
        let getSize: GetSize
        let post: PostEvent
        let create: StreamCreate
        let start: StreamCtl
        let stop: StreamCtl
    }

    private static let api: API? = {
        func sym<T>(_ lib: String, _ name: String, _ t: T.Type) -> T? {
            guard let h = dlopen(lib, RTLD_NOW), let p = dlsym(h, name) else {
                Log.w("Touch Bar: \(name) not found")
                return nil
            }
            return unsafeBitCast(p, to: t)
        }
        let dfr = "/System/Library/PrivateFrameworks/DFRFoundation.framework/DFRFoundation"
        let sky = "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight"
        let cg = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"
        guard let a = sym(dfr, "DFRGetStatus", GetStatus.self), let b = sym(dfr, "DFRSetStatus", SetStatus.self),
              let c = sym(dfr, "DFRGetScreenSize", GetSize.self), let d = sym(dfr, "DFRFoundationPostEventWithMouseActivity", PostEvent.self),
              let e = sym(sky, "SLSDFRDisplayStreamCreate", StreamCreate.self),
              let f = sym(cg, "CGDisplayStreamStart", StreamCtl.self), let g = sym(cg, "CGDisplayStreamStop", StreamCtl.self)
        else { return nil }
        return API(getStatus: a, setStatus: b, getSize: c, post: d, create: e, start: f, stop: g)
    }()

    static var available: Bool { api != nil }

    /// Called with each encoded frame (PNG), throttled.
    var onFrame: ((_ png: Data, _ width: Int, _ height: Int, _ size: CGSize) -> Void)?

    private var stream: CGDisplayStream?
    private var initialStatus: Int32 = 0
    private let queue = DispatchQueue(label: "touchbar-stream")
    private let ci = CIContext(options: [.useSoftwareRenderer: false])
    private var lastSent = Date.distantPast
    private var pending: IOSurface?
    private var flushScheduled = false
    private var lastPNG = Data()
    private let minInterval: TimeInterval = 1.0 / 15
    private var warnedAX = false
    var running: Bool { stream != nil }

    func start() {
        guard stream == nil, let api = Self.api else { return }
        initialStatus = api.getStatus()
        if initialStatus & 0x01 == 0 { api.setStatus(2) }
        guard let s = api.create(0, queue, { [weak self] status, _, surface, _ in
            guard status == .frameComplete, let surface, let self else { return }
            self.enqueue(surface)
        })?.takeUnretainedValue() else {
            Log.w("Touch Bar: display stream could not be created")
            api.setStatus(initialStatus)
            return
        }
        let r = api.start(s)
        guard r == 0 else {
            Log.w("Touch Bar: stream start failed (\(r))")
            api.setStatus(initialStatus)
            return
        }
        stream = s
        lastPNG = Data()
        let size = api.getSize()
        Log.i("Touch Bar emulator started (\(Int(size.width))×\(Int(size.height)) pt)")
    }

    func stop() {
        guard let s = stream, let api = Self.api else { return }
        _ = api.stop(s)
        stream = nil
        api.setStatus(initialStatus)
        Log.i("Touch Bar emulator stopped")
    }

    /// Re-sends the current frame (e.g. right after the phone subscribed).
    func resend() { queue.async { self.lastPNG = Data() } }

    // Frames arrive on `queue`; keep only the newest one and encode at most 15 times a second.
    private func enqueue(_ surface: IOSurface) {
        pending = surface
        let wait = minInterval - Date().timeIntervalSince(lastSent)
        if wait <= 0 {
            flush()
        } else if !flushScheduled {
            flushScheduled = true
            queue.asyncAfter(deadline: .now() + wait) { self.flushScheduled = false; self.flush() }
        }
    }

    private func flush() {
        guard let surface = pending, let api = Self.api else { return }
        pending = nil
        lastSent = Date()
        let image = CIImage(ioSurface: surface)
        guard let cg = ci.createCGImage(image, from: image.extent),
              let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]),
              png != lastPNG else { return }
        lastPNG = png
        onFrame?(png, cg.width, cg.height, api.getSize())
    }

    enum Phase: String { case down, move, up }

    /// Injects a touch at `point` (Touch Bar points, origin bottom-left like the simulator's view).
    func touch(_ phase: Phase, at point: CGPoint) {
        guard running, let api = Self.api else { return }
        if !AXIsProcessTrusted() {
            if !warnedAX {
                warnedAX = true
                Log.w("Touch Bar: taps need Accessibility access (System Settings → Privacy & Security → Accessibility)")
                let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
                _ = AXIsProcessTrustedWithOptions(opts)
            }
        }
        let type: NSEvent.EventType = phase == .down ? .leftMouseDown : phase == .up ? .leftMouseUp : .leftMouseDragged
        api.post(type.rawValue, point)
    }

    var inputAllowed: Bool { AXIsProcessTrusted() }
}
