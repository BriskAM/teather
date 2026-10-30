import Foundation

public enum WireError: Error { case frameTooLarge, invalidFrame }
public struct LineFramer {
    public static let limit = 2_097_152
    private var pending = Data()
    public init() {}
    public mutating func append(_ bytes: Data) throws -> [Data] {
        var lines: [Data] = []
        for byte in bytes {
            if byte == 10 {
                guard !pending.isEmpty else { throw WireError.invalidFrame }
                lines.append(pending); pending = Data()
            } else {
                guard pending.count < Self.limit else { throw WireError.frameTooLarge }
                pending.append(byte)
            }
        }
        return lines
    }
}
public enum Tailnet {
    public static func isIPv4(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        let numbers = parts.compactMap { part -> Int? in
            guard !part.isEmpty, part.count <= 3, part.allSatisfy({ $0.isASCII && $0.isNumber }), part.count == 1 || part.first != "0", let value = Int(part), value <= 255 else { return nil }
            return value
        }
        return numbers.count == 4 && numbers[0] == 100 && (64...127).contains(numbers[1])
    }
    public static func acceptsPeer(_ host: String) -> Bool { isIPv4(host) || host == "127.0.0.1" || host == "::1" || (host.hasPrefix("::ffff:") && isIPv4(String(host.dropFirst(7)))) }
}
public enum Authentication {
    public static func matches(_ candidate: String, _ expected: String) -> Bool {
        let lhs = Array(candidate.utf8), rhs = Array(expected.utf8)
        guard lhs.count == 64, rhs.count == 64 else { return false }
        var different: UInt8 = 0
        for i in 0..<64 { different |= lhs[i] ^ rhs[i] }
        return different == 0
    }
}
public struct NotificationItem: Codable, Equatable {
    public let key: String
    public let revision: String
    public let app: String
    public let package: String
    public let title: String
    public let body: String
    public let dismissible: Bool
    public let sound: Bool
    public init(key: String, revision: String, app: String, package: String, title: String, body: String, dismissible: Bool, sound: Bool) {
        self.key = key; self.revision = revision; self.app = app; self.package = package; self.title = title; self.body = body; self.dismissible = dismissible; self.sound = sound
    }
    public var valid: Bool { !key.isEmpty && key.utf8.count <= 4096 && revision.count == 64 && app.count <= 128 && title.count <= 256 && body.count <= 4000 }
}
public enum SyncEffect: Equatable {
    case present(NotificationItem, silent: Bool)
    case remove(String)
    case dismiss(String, revision: String)
}
public struct NotificationMirror {
    public private(set) var items: [String: NotificationItem] = [:]
    private var locallyDismissed: [String: String] = [:]
    private var pending: [String: (revision: String, date: Date)] = [:]
    public init() {}
    public mutating func sync(_ incoming: [NotificationItem], removed: [String] = [], full: Bool, now: Date = Date()) -> [SyncEffect] {
        var effects: [SyncEffect] = []
        let keys = Set(incoming.map(\.key))
        let deleted = full ? Set(items.keys).subtracting(keys) : Set(removed)
        for key in deleted { items.removeValue(forKey: key); locallyDismissed.removeValue(forKey: key); pending.removeValue(forKey: key); effects.append(.remove(key)) }
        for item in incoming where item.valid {
            if let command = pending[item.key] {
                if now.timeIntervalSince(command.date) < 600 && command.revision == item.revision && item.dismissible { effects.append(.dismiss(item.key, revision: item.revision)) }
                else { pending.removeValue(forKey: item.key) }
            }
            if items[item.key] != item && locallyDismissed[item.key] != item.revision { effects.append(.present(item, silent: full)); locallyDismissed.removeValue(forKey: item.key) }
            items[item.key] = item
        }
        return effects
    }
    public mutating func dismissLocally(_ key: String, now: Date = Date()) -> [SyncEffect] {
        guard let item = items[key] else { return [] }
        locallyDismissed[key] = item.revision
        guard item.dismissible else { return [] }
        pending[key] = (item.revision, now)
        return [.dismiss(key, revision: item.revision)]
    }
    public mutating func resolve(_ key: String, revision: String, result: String) {
        guard pending[key]?.revision == revision else { return }
        if result != "requested" && result != "unavailable" { pending.removeValue(forKey: key) }
    }
    public mutating func clear() -> [String] { let keys = Array(items.keys); self = NotificationMirror(); return keys }
}
