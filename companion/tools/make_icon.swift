// Renders Resources/AppIcon.icns: a dark rounded tile with an analog clock and a calendar strip.
import AppKit

func render(_ px: Int) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = CGFloat(px)
    let inset = s * 0.1
    let tile = NSRect(x: inset, y: inset, width: s - 2 * inset, height: s - 2 * inset)
    NSGradient(starting: NSColor(white: 0.16, alpha: 1), ending: NSColor(white: 0.04, alpha: 1))!
        .draw(in: NSBezierPath(roundedRect: tile, xRadius: tile.width * 0.225, yRadius: tile.width * 0.225), angle: -90)
    // clock
    let c = NSPoint(x: tile.minX + tile.width * 0.32, y: tile.midY)
    let r = tile.width * 0.22
    NSColor.white.setStroke()
    let ring = NSBezierPath(ovalIn: NSRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
    ring.lineWidth = s * 0.022
    ring.stroke()
    func hand(_ deg: CGFloat, _ len: CGFloat, _ w: CGFloat, _ col: NSColor) {
        let a = deg * .pi / 180
        let p = NSBezierPath()
        p.move(to: c)
        p.line(to: NSPoint(x: c.x + sin(a) * len, y: c.y + cos(a) * len))
        p.lineWidth = w
        p.lineCapStyle = .round
        col.setStroke()
        p.stroke()
    }
    hand(300, r * 0.55, s * 0.03, .white)
    hand(60, r * 0.8, s * 0.022, .white)
    hand(200, r * 0.85, s * 0.009, NSColor(red: 1, green: 0.62, blue: 0.04, alpha: 1))
    // calendar dots
    let red = NSColor(red: 1, green: 0.27, blue: 0.23, alpha: 1)
    let gx = tile.minX + tile.width * 0.6
    let cell = tile.width * 0.085
    red.setFill()
    NSBezierPath(roundedRect: NSRect(x: gx, y: tile.midY + cell * 1.6, width: cell * 3.6, height: cell * 0.6), xRadius: cell * 0.2, yRadius: cell * 0.2).fill()
    for row in 0..<3 {
        for col in 0..<4 {
            let x = gx + CGFloat(col) * cell
            let y = tile.midY + cell * 0.6 - CGFloat(row) * cell * 1.1
            let today = row == 1 && col == 2
            (today ? red : NSColor(white: 0.6, alpha: 1)).setFill()
            let d = today ? cell * 0.7 : cell * 0.42
            NSBezierPath(ovalIn: NSRect(x: x + (cell - d) / 2, y: y + (cell - d) / 2, width: d, height: d)).fill()
        }
    }
    NSGraphicsContext.restoreGraphicsState()
    return rep
}

let dir = URL(fileURLWithPath: CommandLine.arguments[1])
try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
for base in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let name = scale == 1 ? "icon_\(base)x\(base).png" : "icon_\(base)x\(base)@2x.png"
        try! render(base * scale).representation(using: .png, properties: [:])!.write(to: dir.appendingPathComponent(name))
    }
}
