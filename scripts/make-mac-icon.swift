import AppKit
import Foundation
let directory = URL(fileURLWithPath: CommandLine.arguments[1])
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
for size in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let pixels = size * scale
        let image = NSImage(size: NSSize(width: pixels, height: pixels))
        image.lockFocus()
        NSColor(calibratedRed: 0.84, green: 0.96, blue: 0.66, alpha: 1).setFill()
        NSBezierPath(roundedRect: NSRect(x: 0, y: 0, width: pixels, height: pixels), xRadius: CGFloat(pixels) * 0.23, yRadius: CGFloat(pixels) * 0.23).fill()
        if let symbol = NSImage(systemSymbolName: "link", accessibilityDescription: nil)?.withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: CGFloat(pixels) * 0.54, weight: .semibold)) {
            let rect = NSRect(x: CGFloat(pixels) * 0.18, y: CGFloat(pixels) * 0.18, width: CGFloat(pixels) * 0.64, height: CGFloat(pixels) * 0.64)
            symbol.draw(in: rect)
        }
        image.unlockFocus()
        let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
        let name = "icon_\(size)x\(size)\(scale == 2 ? "@2x" : "").png"
        try bitmap.representation(using: .png, properties: [:])!.write(to: directory.appendingPathComponent(name))
    }
}
