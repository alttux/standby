// Probe of the private APIs used by sindresorhus/touch-bar-simulator (TouchBarView.swift).
import AppKit
import CoreImage

func sym<T>(_ lib: String, _ name: String, _ t: T.Type) -> T? {
    guard let h = dlopen(lib, RTLD_NOW) else { print("dlopen failed: \(lib) \(String(cString: dlerror()))"); return nil }
    guard let p = dlsym(h, name) else { print("✗ \(name) not found in \(lib)"); return nil }
    print("✓ \(name)")
    return unsafeBitCast(p, to: t)
}
let dfr = "/System/Library/PrivateFrameworks/DFRFoundation.framework/DFRFoundation"
let sky = "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight"
typealias GetStatus = @convention(c) () -> Int32
typealias SetStatus = @convention(c) (Int32) -> Void
typealias GetSize = @convention(c) () -> CGSize
typealias PostEvent = @convention(c) (UInt, CGPoint) -> Void
typealias StreamCreate = @convention(c) (Int32, DispatchQueue, @escaping CGDisplayStreamFrameAvailableHandler) -> Unmanaged<CGDisplayStream>?

let getStatus = sym(dfr, "DFRGetStatus", GetStatus.self)
let setStatus = sym(dfr, "DFRSetStatus", SetStatus.self)
let getSize = sym(dfr, "DFRGetScreenSize", GetSize.self)
let post = sym(dfr, "DFRFoundationPostEventWithMouseActivity", PostEvent.self)
let create = sym(sky, "SLSDFRDisplayStreamCreate", StreamCreate.self)
typealias StreamCtl = @convention(c) (CGDisplayStream) -> Int32
let cg = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"
let streamStart = sym(cg, "CGDisplayStreamStart", StreamCtl.self)
let streamStop = sym(cg, "CGDisplayStreamStop", StreamCtl.self)

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let initial = getStatus?() ?? -1
print("DFRGetStatus = \(initial)")
print("DFRGetScreenSize (before) = \(getSize?() ?? .zero)")
if initial & 1 == 0 { setStatus?(2); print("DFRSetStatus(2)") }
usleep(300_000)
print("DFRGetStatus after = \(getStatus?() ?? -1)")
print("DFRGetScreenSize (after) = \(getSize?() ?? .zero)")

var frames = 0, idle = 0, other = 0
var saved = false
let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "frame.png")
let stream = create?(0, .main) { status, _, surface, _ in
    switch status {
    case .frameComplete:
        frames += 1
        if !saved, let s = surface {
            let out = frames == 1 ? out : out.deletingPathExtension().appendingPathExtension("after.png")
            saved = true
            let ci = CIImage(ioSurface: s)
            print("frame surface \(IOSurfaceGetWidth(s))x\(IOSurfaceGetHeight(s)) pixelFormat=\(IOSurfaceGetPixelFormat(s))")
            let ctx = CIContext()
            if let cg = ctx.createCGImage(ci, from: ci.extent) {
                let rep = NSBitmapImageRep(cgImage: cg)
                try? rep.representation(using: .png, properties: [:])?.write(to: out)
                print("saved \(out.path)")
            }
        }
    case .frameIdle: idle += 1
    default: other += 1; print("stream status \(status.rawValue)")
    }
}?.takeUnretainedValue()
print("stream = \(stream == nil ? "nil" : "created")")
if let st = stream { print("CGDisplayStreamStart = \(streamStart?(st) ?? -999)") }
RunLoop.main.run(until: Date().addingTimeInterval(4))
// tap in the middle of the bar, as the simulator does on mouseDown/mouseUp
if let post, let size = getSize?() {
    let x = CommandLine.arguments.count > 2 ? Double(CommandLine.arguments[2])! : size.width / 2
    post(NSEvent.EventType.leftMouseDown.rawValue, CGPoint(x: x, y: size.height / 2))
    usleep(80_000)
    post(NSEvent.EventType.leftMouseUp.rawValue, CGPoint(x: x, y: size.height / 2))
    saved = false
    print("posted tap")
}
RunLoop.main.run(until: Date().addingTimeInterval(3))
print("frames complete=\(frames) idle=\(idle) other=\(other)")
if let st = stream { _ = streamStop?(st) }
setStatus?(initial)
