import AppKit
import Foundation
import TeatherTransport

@main struct DiskChecks {
    static func main() throws {
        let readOnly = try NetworkDisk.mount(host: "127.0.0.1", secret: String(repeating: "d", count: 64), readOnly: true, allowLoopbackForTesting: true)
        do {
            let contents = try String(contentsOf: readOnly.appendingPathComponent("Read me.txt"), encoding: .utf8)
            precondition(contents.contains("Android emulator"))
            do { try Data("blocked".utf8).write(to: readOnly.appendingPathComponent("must-not-exist.txt")); fatalError("Read-only volume allowed a write") } catch {}
            print("PASS: native macOS WebDAV mount, scoped Android file read, and read-only enforcement")
        } catch { try? NSWorkspace.shared.unmountAndEjectDevice(at: readOnly); throw error }
        try NSWorkspace.shared.unmountAndEjectDevice(at: readOnly)
        let writable = try NetworkDisk.mount(host: "127.0.0.1", secret: String(repeating: "d", count: 64), readOnly: false, allowLoopbackForTesting: true)
        defer { try? NSWorkspace.shared.unmountAndEjectDevice(at: writable) }
        let file = writable.appendingPathComponent("Created on Mac.txt")
        try Data("Written through the mounted Android disk".utf8).write(to: file)
        let readBack = try String(contentsOf: file, encoding: .utf8)
        precondition(readBack == "Written through the mounted Android disk")
        let renamed = writable.appendingPathComponent("Renamed on Mac.txt")
        try FileManager.default.moveItem(at: file, to: renamed)
        try FileManager.default.removeItem(at: renamed)
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("Teather-copy-\(UUID().uuidString).txt")
        try Data("Copied through Finder-compatible filesystem APIs".utf8).write(to: source)
        defer { try? FileManager.default.removeItem(at: source) }
        let copied = writable.appendingPathComponent("Copied from Mac.txt")
        try FileManager.default.copyItem(at: source, to: copied)
        let copiedText = try String(contentsOf: copied, encoding: .utf8)
        precondition(copiedText == "Copied through Finder-compatible filesystem APIs")
        try FileManager.default.removeItem(at: copied)
        print("PASS: native mounted-volume create, read back, rename, delete, file copy, and eject")
    }
}
